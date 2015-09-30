package com.keepit.payments

import com.keepit.common.db.slick.Database
import com.keepit.model.{
  Organization,
  OrganizationRepo,
  OrganizationMembershipRepo,
  OrganizationMembershipStates,
  OrganizationMembership,
  User
}
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.common.logging.Logging

import scala.util.{ Try, Success, Failure }

import com.google.inject.{ Singleton, Inject }

@Singleton
class PaymentsIntegrityChecker @Inject() (
    db: Database,
    clock: Clock,
    organizationRepo: OrganizationRepo,
    organizationMembershipRepo: OrganizationMembershipRepo,
    accountLockHelper: AccountLockHelper,
    accountEventRepo: AccountEventRepo,
    paidAccountRepo: PaidAccountRepo,
    planManagementCommander: PlanManagementCommander) extends Logging {

  private def freezeAccount(orgId: Id[Organization]): Unit = {
    //TODO: once things are stable and eveything is backfilled this should email whoever is repsonsible for checking and unfreezing accounts
    db.readWrite { implicit session =>
      paidAccountRepo.save(paidAccountRepo.getByOrgId(orgId).freeze)
    }

  }

  private def processMembershipsForAccount(orgId: Id[Organization]): Try[Option[Int]] = {
    Try {
      accountLockHelper.maybeSessionWithAccountLock(orgId) { implicit session =>
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
            val initialEvent: AccountEvent = events.head
            initialEvent.action match {
              case AccountEventAction.UserAdded(_) =>
              case _ => throw new Exception(s"""First user event for $userId on org $orgId was not an "added" event. This should never happen.""")
            }
            userId -> events.tail.foldLeft(true) {
              case (isMember, event) =>
                event.action match {
                  case AccountEventAction.UserAdded(userId) if isMember => throw new Exception(s"""Consecutive "added" events for $userId on org $orgId. This should never happen.""")
                  case AccountEventAction.UserAdded(userId) => true
                  case AccountEventAction.UserRemoved(userId) if !isMember => throw new Exception(s"""Consecutive "removed" events for $userId on org $orgId. This should never happen.""")
                  case AccountEventAction.UserRemoved(userId) => false
                  case _ => throw new Exception("Bad Database query includes things it shouldn't. This should never happen.")
                }
            }
        }

        val perceivedMemberIds: Set[Id[User]] = perceivedStateByUser.filter(_._2).map(_._1).toSet
        val perceivedExMemberIds: Set[Id[User]] = perceivedStateByUser.filterNot(_._2).map(_._1).toSet

        val perceivedActiveButActuallyInactive = (perceivedMemberIds & exMemberIds) | (perceivedMemberIds -- memberIds -- exMemberIds) //first part is ones that were active at some point, second part is completely phantom ones
        val perceivedInactiveButActuallyActive = memberIds -- perceivedMemberIds

        //TODO: one things are stable and everything is backfilled, the three code blocks below should airbake instead of logging
        perceivedActiveButActuallyInactive.foreach { userId =>
          log.info(s"[AEIC] Events show user $userId as an active member of $orgId, which is not correct. Creating new UserRemoved event.")
          planManagementCommander.registerRemovedUserHelper(orgId, userId, ActionAttribution(None, None))
        }
        perceivedInactiveButActuallyActive.foreach { userId =>
          log.info(s"[AEIC] Events show user $userId as an inactive member of $orgId, which is not correct. Creating new UserAdded event.")
          planManagementCommander.registerNewUserHelper(orgId, userId, ActionAttribution(None, None))
        }
        if (account.activeUsers != memberIds.size) {
          log.info(s"[AEIC] Total active user count on account for org $orgId not correct. Is: ${account.activeUsers}. Should: ${memberIds.size}. Fixing.")
          paidAccountRepo.save(account.copy(activeUsers = memberIds.size))
        }

        perceivedInactiveButActuallyActive.size + perceivedActiveButActuallyInactive.size
      }
    }

  }

  def checkMemberships(): Unit = {
    //the following two lines will need adjustment as the number of organizations grow
    val modulus = 7 //days of the week
    val partition = clock.now.getDayOfWeek() - 1 //what to do today
    val orgIds = db.readOnlyReplica { implicit session => organizationRepo.getIdSubsetByModulus(modulus, partition) }
    orgIds.foreach { orgId =>
      processMembershipsForAccount(orgId) match {
        case Success(Some(0)) => log.info(s"[AEIC] Successfully processed org $orgId. No discrepancies found.")
        case Success(Some(n)) => {
          log.info(s"[AEIC] Successfully processed org $orgId. $n discrepancies found. Freezing Account.")
          freezeAccount(orgId)
        }
        case Success(None) => log.info(s"[AEIC] Could not process org $orgId due to account being locked.")
        case Failure(ex) => {
          log.info(s"[AEIC] An error occured during the check: ${ex.getMessage()}. Freezing Account", ex)
          freezeAccount(orgId)
        }
      }
    }
  }

}
