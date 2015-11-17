package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.classify.{ NormalizedHostname, Domain, DomainRepo }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.mail.EmailAddress._
import com.keepit.model._
import play.api.libs.json._
import com.keepit.common.time.{ currentDateTime, DEFAULT_DATE_TIME_ZONE }

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext }

@ImplementedBy(classOf[OrganizationDomainOwnershipCommanderImpl])
trait OrganizationDomainOwnershipCommander {
  def getDomainsOwned(orgId: Id[Organization]): Set[NormalizedHostname]
  def addDomainOwnership(request: OrganizationDomainAddRequest): Either[OrganizationFail, OrganizationDomainAddResponse]
  def removeDomainOwnership(request: OrganizationDomainRemoveRequest): Option[OrganizationFail]
  def getSharedEmails(userId: Id[User], orgId: Id[Organization]): Set[EmailAddress]
  def getSharedEmailsHelper(userId: Id[User], orgId: Id[Organization])(implicit session: RSession): Set[EmailAddress]
  def hideOrganizationForUser(userId: Id[User], orgId: Id[Organization]): Unit
  def addPendingOwnershipByEmail(orgId: Id[Organization], userId: Id[User], emailAddress: EmailAddress): Option[OrganizationFail]
  def addOwnershipsForPendingOrganizations(userId: Id[User], emailAddress: EmailAddress): Map[Id[Organization], Option[OrganizationFail]]
  def autoJoinOrgViaEmail(verifiedEmail: UserEmailAddress)(implicit session: RWSession): Unit
}

object OrganizationDomainOwnershipCommander {
  case class OwnDomainSuccess(domain: Domain, ownership: OrganizationDomainOwnership)
}

@Singleton
class OrganizationDomainOwnershipCommanderImpl @Inject() (
    db: Database,
    orgDomainOwnershipRepo: OrganizationDomainOwnershipRepo,
    orgConfigurationRepo: OrganizationConfigurationRepo,
    domainRepo: DomainRepo,
    orgExperimentRepo: OrganizationExperimentRepo,
    userEmailAddressRepo: UserEmailAddressRepo,
    userEmailAddressCommander: UserEmailAddressCommander,
    permissionCommander: PermissionCommander,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCommander: OrganizationMembershipCommander,
    userValueRepo: UserValueRepo,
    airbrake: AirbrakeNotifier,
    implicit val executionContext: ScalaExecutionContext) extends OrganizationDomainOwnershipCommander with Logging {

  def getDomainsOwned(orgId: Id[Organization]): Set[NormalizedHostname] = db.readOnlyReplica { implicit session =>
    orgDomainOwnershipRepo.getOwnershipsForOrganization(orgId).map(_.normalizedHostname).toSet
  }

  private def getValidationError(request: OrganizationDomainRequest)(implicit session: RSession): Option[OrganizationFail] = {
    val (requesterId, orgId, domainName) = (request.requesterId, request.orgId, request.domain)
    val domainFailOpt = NormalizedHostname.fromHostname(domainName) match {
      case None => Some(OrganizationFail.INVALID_DOMAIN_NAME)
      case Some(validDomain) => {
        domainRepo.get(validDomain).collect {
          case domain if domain.isEmailProvider => OrganizationFail.DOMAIN_IS_EMAIL_PROVIDER
        }
      }
    }
    lazy val permissionFailOpt = {
      Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        .filter(_ => !permissionCommander.getOrganizationPermissions(orgId, Some(requesterId)).contains(OrganizationPermission.MANAGE_PLAN))
    }
    lazy val verifiedEmailOpt = {
      Some(OrganizationFail.UNVERIFIED_EMAIL_DOMAIN).filter { _ =>
        !userEmailAddressRepo.getAllByUser(requesterId).exists(email => email.address.hostname.trim.toLowerCase == domainName.trim.toLowerCase && email.verified)
      }
    }
    request match {
      case _: OrganizationDomainAddRequest => domainFailOpt.orElse(permissionFailOpt).orElse(verifiedEmailOpt)
      case _ => domainFailOpt.orElse(permissionFailOpt)
    }
  }

  def addDomainOwnership(request: OrganizationDomainAddRequest): Either[OrganizationFail, OrganizationDomainAddResponse] = {
    db.readOnlyReplica(implicit session => getValidationError(request)) match {
      case Some(fail) => Left(fail)
      case None => {
        val ownership = unsafeAddDomainOwnership(request.orgId, request.domain)
        Right(OrganizationDomainAddResponse(request, ownership.normalizedHostname))
      }
    }
  }

  def unsafeAddDomainOwnership(orgId: Id[Organization], domainName: String): OrganizationDomainOwnership = {
    val normalizedHostname = NormalizedHostname.fromHostname(domainName).get
    val ownership = db.readWrite { implicit session =>
      val domain = domainRepo.intern(normalizedHostname)
      orgDomainOwnershipRepo.getDomainOwnershipBetween(orgId, normalizedHostname, excludeState = None) match {
        case Some(ownership) if ownership.isActive => ownership
        case inactiveOpt => orgDomainOwnershipRepo.save(OrganizationDomainOwnership(id = inactiveOpt.flatMap(_.id), state = OrganizationDomainOwnershipStates.ACTIVE, organizationId = orgId, normalizedHostname = domain.hostname))
      }
    }

    db.readWriteAsync { implicit session =>
      val canVerifyToJoin = orgConfigurationRepo.getByOrgId(orgId).settings.settingFor(Feature.JoinByVerifying).contains(FeatureSetting.NONMEMBERS)
      if (canVerifyToJoin) sendVerificationEmailsToAllPotentialMembers(ownership)
    }

    ownership
  }

  private def sendVerificationEmailsToAllPotentialMembers(ownership: OrganizationDomainOwnership)(implicit session: RWSession): Unit = {
    lazy val orgMembers = orgMembershipRepo.getAllByOrgId(ownership.organizationId)
    val usersToEmail = userEmailAddressRepo.getByDomain(ownership.normalizedHostname)
      .filter { userEmail =>
        !userEmail.address.address.contains("+test@kifi.com") && userEmail.lastVerificationSent.forall(lastSent => lastSent.plusDays(1).isBefore(currentDateTime)) &&
          !orgMembers.exists(_.userId == userEmail.userId) && !userValueRepo.getValue(userEmail.userId, UserValues.hideEmailDomainOrganizations).as[Set[Id[Organization]]].contains(ownership.organizationId)
      }
    usersToEmail.foreach(userEmailAddressCommander.sendVerificationEmailHelper)
  }

  def removeDomainOwnership(request: OrganizationDomainRemoveRequest): Option[OrganizationFail] = {
    db.readOnlyReplica(implicit session => getValidationError(request)) match {
      case Some(fail) => Some(fail)
      case None => {
        val ownership = unsafeRemoveDomainOwnership(request.orgId, request.domain)
        None
      }
    }
  }

  def unsafeRemoveDomainOwnership(orgId: Id[Organization], domainName: String): Unit = {
    val normalizedHostname = NormalizedHostname.fromHostname(domainName).get
    db.readWrite { implicit session =>
      orgDomainOwnershipRepo.getDomainOwnershipBetween(orgId, normalizedHostname).map { ownership =>
        orgDomainOwnershipRepo.save(ownership.copy(state = OrganizationDomainOwnershipStates.INACTIVE))
      }
    }
  }

  def getSharedEmails(userId: Id[User], orgId: Id[Organization]): Set[EmailAddress] = db.readOnlyReplica { implicit session =>
    getSharedEmailsHelper(userId, orgId)
  }

  def getSharedEmailsHelper(userId: Id[User], orgId: Id[Organization])(implicit session: RSession): Set[EmailAddress] = {
    val emails = userEmailAddressRepo.getAllByUser(userId).map(_.address)
    val orgDomains = orgDomainOwnershipRepo.getOwnershipsForOrganization(orgId).map(_.normalizedHostname).toSet
    emails.filter { email =>
      val hostnameOpt = NormalizedHostname.fromHostname(email.hostname)
      hostnameOpt.exists(orgDomains.contains)
    }.toSet
  }

  def hideOrganizationForUser(userId: Id[User], orgId: Id[Organization]): Unit = {
    db.readWrite { implicit session =>
      val newOrgsToIgnore = userValueRepo.getValue(userId, UserValues.hideEmailDomainOrganizations).as[Set[Id[Organization]]] + orgId
      userValueRepo.setValue(userId, UserValueName.HIDE_EMAIL_DOMAIN_ORGANIZATIONS, Json.toJson(newOrgsToIgnore))
    }
  }

  def addPendingOwnershipByEmail(orgId: Id[Organization], userId: Id[User], emailAddress: EmailAddress): Option[OrganizationFail] = {
    db.readWrite { implicit session =>
      getValidationError(OrganizationDomainPendingAddRequest(userId, orgId, emailAddress.hostname)) match {
        case Some(fail) => Some(fail)
        case None =>
          val orgsToAddByEmail = userValueRepo.getValue(userId, UserValues.pendingOrgDomainOwnershipByEmail).as[Map[String, Seq[Id[Organization]]]]
          val newOrgsToAdd = orgsToAddByEmail.getOrElse(emailAddress.address, Seq.empty) :+ orgId
          val newOrgsToAddByEmail = orgsToAddByEmail + (emailAddress.address -> newOrgsToAdd)
          userValueRepo.setValue(userId, UserValueName.PENDING_ORG_DOMAIN_OWNERSHIP_BY_EMAIL, Json.toJson(newOrgsToAddByEmail))
          None
      }
    }
  }

  def addOwnershipsForPendingOrganizations(userId: Id[User], emailAddress: EmailAddress): Map[Id[Organization], Option[OrganizationFail]] = {
    val orgsToAddByEmail = db.readOnlyReplica(implicit s => userValueRepo.getValue(userId, UserValues.pendingOrgDomainOwnershipByEmail)).as[Map[String, Seq[Id[Organization]]]]
    val orgsToAdd = orgsToAddByEmail.getOrElse(emailAddress.address, Set.empty)
    orgsToAdd.map { orgId =>
      addDomainOwnership(OrganizationDomainAddRequest(userId, orgId, emailAddress.hostname)) match {
        case Left(fail) =>
          if (fail == OrganizationFail.DOMAIN_IS_EMAIL_PROVIDER || fail == OrganizationFail.INVALID_DOMAIN_NAME) {
            throw new Exception(s"invalid domain pending ownership upon validation of user ${userId.id}'s email ${emailAddress.address}. domains should be validated before storing.")
          }
          orgId -> Some(fail)
        case Right(success) => orgId -> None
      }
    }.toMap
  }

  def autoJoinOrgViaEmail(verifiedEmail: UserEmailAddress)(implicit session: RWSession): Unit = {
    NormalizedHostname.fromHostname(verifiedEmail.address.hostname)
      .map(domain => orgDomainOwnershipRepo.getOwnershipsForDomain(domain).map(_.organizationId)).getOrElse(Set.empty)
      .filter(orgId => permissionCommander.getOrganizationPermissions(orgId, Some(verifiedEmail.userId)).contains(OrganizationPermission.JOIN_BY_VERIFYING))
      .diff(userValueRepo.getValue(verifiedEmail.userId, UserValues.hideEmailDomainOrganizations).as[Set[Id[Organization]]])
      .foreach { orgId =>
        val addRequest = OrganizationMembershipAddRequest(orgId, requesterId = verifiedEmail.userId, targetId = verifiedEmail.userId)
        orgMembershipCommander.addMembershipHelper(addRequest)
      }
  }
}
