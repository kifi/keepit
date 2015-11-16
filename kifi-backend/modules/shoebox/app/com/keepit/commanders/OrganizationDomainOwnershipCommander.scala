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
import play.api.libs.json.Json._
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext }

@ImplementedBy(classOf[OrganizationDomainOwnershipCommanderImpl])
trait OrganizationDomainOwnershipCommander {
  def getDomainsOwned(orgId: Id[Organization]): Set[NormalizedHostname]
  def addDomainOwnership(request: OrganizationDomainRequest): Either[OrganizationFail, OrganizationDomainResponse]
  def removeDomainOwnership(request: OrganizationDomainRequest): Option[OrganizationFail]
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
    domainRepo: DomainRepo,
    orgConfigurationRepo: OrganizationConfigurationRepo,
    userEmailAddressRepo: UserEmailAddressRepo,
    userEmailAddressCommander: UserEmailAddressCommander,
    permissionCommander: PermissionCommander,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCommander: OrganizationMembershipCommander,
    userValueRepo: UserValueRepo,
    airbrake: AirbrakeNotifier,
    implicit val executionContext: ScalaExecutionContext) extends OrganizationDomainOwnershipCommander with Logging {

  override def getDomainsOwned(orgId: Id[Organization]): Set[NormalizedHostname] = db.readOnlyReplica { implicit session =>
    orgDomainOwnershipRepo.getOwnershipsForOrganization(orgId).map(_.normalizedHostname).toSet
  }

  private def getValidationError(request: OrganizationDomainRequest)(implicit session: RSession): Option[OrganizationFail] = {
    val OrganizationDomainRequest(requesterId, orgId, domainName) = request
    lazy val permissionFailOpt = {
      Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        .filter(_ => !permissionCommander.getOrganizationPermissions(orgId, Some(requesterId)).contains(OrganizationPermission.MANAGE_PLAN))
    }
    lazy val domainFailOpt = NormalizedHostname.fromHostname(domainName) match {
      case None => Some(OrganizationFail.INVALID_DOMAIN_NAME)
      case Some(validDomain) => {
        domainRepo.get(validDomain).collect {
          case domain if domain.isEmailProvider => OrganizationFail.DOMAIN_IS_EMAIL_PROVIDER
        }
      }
    }
    domainFailOpt.orElse(permissionFailOpt)
  }

  def addDomainOwnership(request: OrganizationDomainRequest): Either[OrganizationFail, OrganizationDomainResponse] = {
    db.readOnlyReplica(implicit session => getValidationError(request)) match {
      case Some(fail) => Left(fail)
      case None => {
        val ownership = unsafeAddDomainOwnership(request.orgId, request.domain)
        Right(OrganizationDomainResponse(request, ownership.normalizedHostname))
      }
    }
  }

  def unsafeAddDomainOwnership(orgId: Id[Organization], domainName: String): OrganizationDomainOwnership = {
    val normalizedHostname = NormalizedHostname.fromHostname(domainName).get
    val ownership = db.readWrite { implicit session =>
      val domain = domainRepo.intern(normalizedHostname)
      orgDomainOwnershipRepo.getDomainOwnershipBetween(orgId, normalizedHostname) match {
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
    val orgMembers = orgMembershipRepo.getAllByOrgId(ownership.organizationId)
    val usersToEmail = userEmailAddressRepo.getByDomain(ownership.normalizedHostname)
      .filter(userEmail => !orgMembers.exists(_.userId == userEmail.userId))
      .filter(userEmail => !userValueRepo.getValue(userEmail.userId, UserValues.hideEmailDomainOrganizations).as[Set[Id[Organization]]].contains(ownership.organizationId))
    usersToEmail.foreach(userEmailAddressCommander.sendVerificationEmailHelper)
  }

  override def removeDomainOwnership(request: OrganizationDomainRequest): Option[OrganizationFail] = {
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

  override def getSharedEmails(userId: Id[User], orgId: Id[Organization]): Set[EmailAddress] = db.readOnlyReplica { implicit session =>
    getSharedEmailsHelper(userId, orgId)
  }

  override def getSharedEmailsHelper(userId: Id[User], orgId: Id[Organization])(implicit session: RSession): Set[EmailAddress] = {
    val emails = userEmailAddressRepo.getAllByUser(userId).map(_.address)
    val orgDomains = orgDomainOwnershipRepo.getOwnershipsForOrganization(orgId).map(_.normalizedHostname).toSet
    emails.filter { email =>
      val hostnameOpt = NormalizedHostname.fromHostname(email.hostname)
      hostnameOpt.exists(orgDomains.contains)
    }.toSet
  }

  override def hideOrganizationForUser(userId: Id[User], orgId: Id[Organization]): Unit = {
    db.readWrite { implicit session =>
      val newOrgsToIgnore = userValueRepo.getValue(userId, UserValues.hideEmailDomainOrganizations).as[Set[Id[Organization]]] + orgId
      userValueRepo.setValue(userId, UserValueName.HIDE_EMAIL_DOMAIN_ORGANIZATIONS, Json.toJson(newOrgsToIgnore))
    }
  }

  override def addPendingOwnershipByEmail(orgId: Id[Organization], userId: Id[User], emailAddress: EmailAddress): Option[OrganizationFail] = {
    db.readWrite { implicit session =>
      getValidationError(OrganizationDomainRequest(userId, orgId, emailAddress.hostname)) match {
        case Some(fail) => Some(fail)
        case _ =>
          val orgsToAddByEmail = userValueRepo.getValue(userId, UserValues.pendingOrgDomainOwnershipByEmail).as[Map[String, Seq[Id[Organization]]]]
          val newOrgsToAdd = orgsToAddByEmail.getOrElse(emailAddress.address, Seq.empty) :+ orgId
          val newOrgsToAddByEmail = orgsToAddByEmail + (emailAddress.address -> newOrgsToAdd)
          userValueRepo.setValue(userId, UserValueName.PENDING_ORG_DOMAIN_OWNERSHIP_BY_EMAIL, Json.toJson(newOrgsToAddByEmail))
          None
      }
    }
  }

  override def addOwnershipsForPendingOrganizations(userId: Id[User], emailAddress: EmailAddress): Map[Id[Organization], Option[OrganizationFail]] = {
    val orgsToAddByEmail = db.readOnlyReplica(implicit s => userValueRepo.getValue(userId, UserValues.pendingOrgDomainOwnershipByEmail)).as[Map[String, Seq[Id[Organization]]]]
    val orgsToAdd = orgsToAddByEmail.getOrElse(emailAddress.address, Set.empty)
    orgsToAdd.map { orgId =>
      addDomainOwnership(OrganizationDomainRequest(userId, orgId, emailAddress.hostname)) match {
        case Left(fail) =>
          if (fail == OrganizationFail.DOMAIN_IS_EMAIL_PROVIDER || fail == OrganizationFail.INVALID_DOMAIN_NAME) {
            throw new Exception(s"invalid domain pending ownership upon validation of user ${userId.id}'s email ${emailAddress.address}. domains should be validated before storing.")
          }
          orgId -> Some(fail)
        case Right(success) => orgId -> None
      }
    }.toMap
  }

  override def autoJoinOrgViaEmail(verifiedEmail: UserEmailAddress)(implicit session: RWSession): Unit = {
    NormalizedHostname.fromHostname(verifiedEmail.address.hostname)
      .map(domain => orgDomainOwnershipRepo.getOwnershipsForDomain(domain).map(_.organizationId)).getOrElse(Set.empty)
      .diff(userValueRepo.getValue(verifiedEmail.userId, UserValues.hideEmailDomainOrganizations).as[Set[Id[Organization]]])
      .foreach { orgId =>
        val addRequest = OrganizationMembershipAddRequest(orgId, requesterId = verifiedEmail.userId, targetId = verifiedEmail.userId)
        orgMembershipCommander.addMembershipHelper(addRequest)
      }
  }
}
