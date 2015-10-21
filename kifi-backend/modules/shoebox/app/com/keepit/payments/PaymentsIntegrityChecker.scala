package com.keepit.payments

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.HttpClient
import com.keepit.common.time._
import com.keepit.model.{ Organization, OrganizationMembership, OrganizationMembershipRepo, OrganizationMembershipStates, OrganizationRepo, User }
import com.kifi.macros.json
import play.api.Mode.Mode
import play.api.libs.json._

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success, Try }

sealed abstract class PaymentsIntegrityError(val value: String) {
  def dump: JsValue
}
object PaymentsIntegrityError {
  private val accountLockError = "could_not_get_account_lock"
  private val accountBalanceError = "inconsistent_account_balance"
  private val missingOrgMemberError = "missing_organization_member"
  private val extraOrgMemberError = "extra_organization_member"
  @json case class CouldNotGetAccountLock(memo: String) extends PaymentsIntegrityError(accountLockError) {
    def dump = Json.toJson(this)(implicitly[Format[CouldNotGetAccountLock]])
  }
  @json case class InconsistentAccountBalance(computedBalance: DollarAmount, accountBalance: DollarAmount) extends PaymentsIntegrityError(accountBalanceError) {
    def dump = Json.toJson(this)(implicitly[Format[InconsistentAccountBalance]])
  }
  @json case class MissingOrganizationMember(missingMember: Id[User]) extends PaymentsIntegrityError(missingOrgMemberError) {
    def dump = Json.toJson(this)(implicitly[Format[MissingOrganizationMember]])
  }
  @json case class ExtraOrganizationMember(extraMember: Id[User]) extends PaymentsIntegrityError(extraOrgMemberError) {
    def dump = Json.toJson(this)(implicitly[Format[ExtraOrganizationMember]])
  }

  implicit val dbFormat: Format[PaymentsIntegrityError] = Format(
    Reads { j =>
      val err = (j.as[JsObject] \ "error").as[String]
      err match {
        case `accountLockError` => (j \ "data").validate[CouldNotGetAccountLock]
        case `accountBalanceError` => (j \ "data").validate[InconsistentAccountBalance]
        case `missingOrgMemberError` => (j \ "data").validate[MissingOrganizationMember]
        case `extraOrgMemberError` => (j \ "data").validate[ExtraOrganizationMember]
      }
    },
    Writes { err => Json.obj("error" -> err.value, "data" -> err.dump) }
  )
}

@Singleton
class PaymentsIntegrityChecker @Inject() (
    db: Database,
    clock: Clock,
    organizationRepo: OrganizationRepo,
    organizationMembershipRepo: OrganizationMembershipRepo,
    accountLockHelper: AccountLockHelper,
    accountEventRepo: AccountEventRepo,
    eventTrackingCommander: AccountEventTrackingCommander,
    paidAccountRepo: PaidAccountRepo,
    planManagementCommander: PlanManagementCommander,
    httpClient: HttpClient,
    mode: Mode,
    airbrake: AirbrakeNotifier,
    implicit val defaultContext: ExecutionContext) extends Logging {

  private def freezeAccount(orgId: Id[Organization]): Unit = {
    db.readWrite { implicit session =>
      paidAccountRepo.save(paidAccountRepo.getByOrgId(orgId).freeze)
    }
  }

  private def processMembershipsForAccount(orgId: Id[Organization]): Set[PaymentsIntegrityError] = accountLockHelper.maybeSessionWithAccountLock(orgId) { implicit session =>
    val memberships: Map[Id[User], OrganizationMembership] = organizationMembershipRepo.getAllByOrgId(orgId, excludeState = None).map(m => m.userId -> m).toMap
    val memberIds: Set[Id[User]] = memberships.values.filter(_.state == OrganizationMembershipStates.ACTIVE).map(_.userId).toSet
    val exMemberIds: Set[Id[User]] = memberships.values.filter(_.state == OrganizationMembershipStates.INACTIVE).map(_.userId).toSet

    val account = paidAccountRepo.getByOrgId(orgId)
    val accountId = account.id.get
    val eventsByUser: Map[Id[User], Seq[AccountEvent]] = accountEventRepo.getMembershipEventsInOrder(accountId).groupBy { event =>
      event.action match {
        case AccountEventAction.UserAdded(userId) => userId
        case AccountEventAction.UserRemoved(userId) => userId
        case _ => throw new Exception("Bad Database query includes things it shouldn't. This should never happen.")
      }
    }

    val perceivedStateByUser: Map[Id[User], Boolean] = eventsByUser.map {
      case (userId, events) =>
        log.info(s"[AEIC] Processing ${events.length} events for $userId on $orgId.")
        val initialEvent: AccountEvent = events.head
        initialEvent.action match {
          case AccountEventAction.UserAdded(_) =>
          case _ => throw new Exception(s"""First user event for $userId on org $orgId was not an "added" event. This should never happen.""")
        }
        userId -> events.tail.foldLeft(true) {
          case (isMember, event) =>
            event.action match {
              case AccountEventAction.UserAdded(`userId`) if isMember => throw new Exception(s"""Consecutive "added" events for $userId on org $orgId. This should never happen.""")
              case AccountEventAction.UserAdded(`userId`) => true
              case AccountEventAction.UserRemoved(`userId`) if !isMember => throw new Exception(s"""Consecutive "removed" events for $userId on org $orgId. This should never happen.""")
              case AccountEventAction.UserRemoved(`userId`) => false
              case _ => throw new Exception("Bad Database query includes things it shouldn't. This should never happen.")
            }
        }
    }

    val perceivedMemberIds: Set[Id[User]] = perceivedStateByUser.filter(_._2).keySet
    val perceivedExMemberIds: Set[Id[User]] = perceivedStateByUser.filterNot(_._2).keySet

    val perceivedActiveButActuallyInactive = (perceivedMemberIds & exMemberIds) | (perceivedMemberIds -- memberIds -- exMemberIds) //first part is ones that were active at some point, second part is completely phantom ones
    val perceivedInactiveButActuallyActive = memberIds -- perceivedMemberIds

    val extraMemberErrors: Set[PaymentsIntegrityError] = perceivedActiveButActuallyInactive.map { userId =>
      log.info(s"[AEIC] Events show user $userId as an active member of $orgId, which is not correct. Creating new UserRemoved event.")
      planManagementCommander.registerRemovedUserHelper(orgId, userId, ActionAttribution(None, None))
      PaymentsIntegrityError.ExtraOrganizationMember(userId)
    }
    val missingMemberErrors: Set[PaymentsIntegrityError] = perceivedInactiveButActuallyActive.map { userId =>
      log.info(s"[AEIC] Events show user $userId as an inactive member of $orgId, which is not correct. Creating new UserAdded event.")
      planManagementCommander.registerNewUserHelper(orgId, userId, ActionAttribution(None, None))
      PaymentsIntegrityError.MissingOrganizationMember(userId)
    }
    if (account.activeUsers != memberIds.size) {
      log.info(s"[AEIC] Total active user count on account for org $orgId not correct. Is: ${account.activeUsers}. Should: ${memberIds.size}. Fixing.")
      paidAccountRepo.save(account.copy(activeUsers = memberIds.size))
    }

    extraMemberErrors ++ missingMemberErrors
  }.getOrElse(Set(PaymentsIntegrityError.CouldNotGetAccountLock("member check")))

  private def processBalancesForAccount(orgId: Id[Organization]): Set[PaymentsIntegrityError] = accountLockHelper.maybeSessionWithAccountLock(orgId) { implicit session =>
    val account = paidAccountRepo.getByOrgId(orgId)
    val accountId = account.id.get
    val accountEvents = accountEventRepo.getAllByAccount(accountId)

    val creditChanges = accountEvents.map(_.creditChange)
    val computedCredit = creditChanges.fold(DollarAmount.ZERO)(_ + _)
    if (computedCredit == account.credit) Set.empty[PaymentsIntegrityError]
    else {
      log.error(s"[AEIC] Computed credit for $orgId = $computedCredit but the account shows a credit of ${account.credit}")
      Set[PaymentsIntegrityError](PaymentsIntegrityError.InconsistentAccountBalance(computedBalance = computedCredit, accountBalance = account.credit))
    }
  }.getOrElse(Set[PaymentsIntegrityError](PaymentsIntegrityError.CouldNotGetAccountLock("balance check")))

  private def checkAccount(orgId: Id[Organization]): Set[PaymentsIntegrityError] = {
    Set(
      processMembershipsForAccount(orgId),
      processBalancesForAccount(orgId)
    ).flatten
  }

  def checkAccounts(modulus: Int = 7): Unit = {
    // we are going to process ~1/modulus of the orgs, based on the date
    val partition = clock.now.getDayOfYear % modulus
    val orgIds = db.readOnlyReplica { implicit session => paidAccountRepo.getIdSubsetByModulus(modulus, partition) }

    orgIds.foreach { orgId =>
      Try(checkAccount(orgId)) match {
        case Success(errs) if errs.nonEmpty =>
          freezeAccount(orgId)
          db.readWrite { implicit session =>
            val accountId = paidAccountRepo.getAccountId(orgId)
            errs.foreach { err => eventTrackingCommander.track(AccountEvent.fromIntegrityError(accountId, err)) }
          }
        case Success(Set.empty) =>
          log.info(s"[AEIC] Checked on $orgId and it was totally fine")
        case Failure(ex) =>
          log.error(s"[AEIC] An error occured during the check for $orgId: ${ex.getMessage}. Freezing Account", ex)
          airbrake.notify(ex)
          freezeAccount(orgId)
      }
    }
  }

}
