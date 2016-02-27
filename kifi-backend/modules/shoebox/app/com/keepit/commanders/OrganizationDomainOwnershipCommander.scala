package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.classify.{ NormalizedHostname, Domain, DomainRepo }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ LocalPostOffice, SystemEmailAddress, ElectronicMail, EmailAddress }
import com.keepit.common.mail.EmailAddress._
import com.keepit.heimdal.HeimdalContextBuilder
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import play.api.libs.json._
import com.keepit.common.time.{ Clock, currentDateTime, DEFAULT_DATE_TIME_ZONE }

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
  def sendMembershipConfirmationEmail(request: OrganizationDomainSendMemberConfirmationRequest): Either[OrganizationFail, ElectronicMail]
}

object OrganizationDomainOwnershipCommander {
  case class OwnDomainSuccess(domain: Domain, ownership: OrganizationDomainOwnership)
}

@Singleton
class OrganizationDomainOwnershipCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    orgDomainOwnershipRepo: OrganizationDomainOwnershipRepo,
    orgConfigurationRepo: OrganizationConfigurationRepo,
    domainRepo: DomainRepo,
    userEmailAddressRepo: UserEmailAddressRepo,
    userEmailAddressCommander: UserEmailAddressCommander,
    permissionCommander: PermissionCommander,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgAnalytics: OrganizationAnalytics,
    userRepo: UserRepo,
    userValueRepo: UserValueRepo,
    airbrake: AirbrakeNotifier,
    postOffice: LocalPostOffice,
    fortytwoConfig: FortyTwoConfig,
    heimdalContextBuilder: HeimdalContextBuilder,
    clock: Clock,
    implicit val executionContext: ScalaExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) extends OrganizationDomainOwnershipCommander with Logging {

  def getDomainsOwned(orgId: Id[Organization]): Set[NormalizedHostname] = db.readOnlyReplica { implicit session =>
    orgDomainOwnershipRepo.getOwnershipsForOrganization(orgId).map(_.normalizedHostname).toSet
  }

  private def getValidationError(request: OrganizationDomainRequest)(implicit session: RSession): Option[OrganizationFail] = {
    val (requesterId, orgId, domainName) = (request.requesterId, request.orgId, request.domain)

    val invalidDomain = Some(OrganizationFail.INVALID_DOMAIN_NAME).filter(_ => NormalizedHostname.fromHostname(domainName).isEmpty)
    lazy val emailProvider = Some(OrganizationFail.DOMAIN_IS_EMAIL_PROVIDER).filter { _ =>
      NormalizedHostname.fromHostname(domainName).flatMap(domainRepo.get(_)).exists(_.isEmailProvider)
    }
    lazy val domainNotOwned = Some(OrganizationFail.DOMAIN_OWNERSHIP_NOT_FOUND).filter { _ =>
      !NormalizedHostname.fromHostname(domainName).exists { hostname =>
        orgDomainOwnershipRepo.getOwnershipsForOrganization(orgId).exists(_.normalizedHostname == hostname)
      }
    }
    lazy val managePermission = Some(OrganizationFail.INSUFFICIENT_PERMISSIONS).filter { _ =>
      !permissionCommander.getOrganizationPermissions(orgId, Some(requesterId)).contains(OrganizationPermission.MANAGE_PLAN)
    }
    lazy val joinViaEmailPermission = Some(OrganizationFail.INSUFFICIENT_PERMISSIONS).filter { _ =>
      !permissionCommander.getOrganizationPermissions(orgId, Some(requesterId)).contains(OrganizationPermission.JOIN_BY_VERIFYING)
    }

    lazy val verifiedEmail = {
      Some(OrganizationFail.UNVERIFIED_EMAIL_DOMAIN).filter { _ =>
        !userEmailAddressRepo.getAllByUser(requesterId).exists(email => email.address.hostname.trim.toLowerCase == domainName.trim.toLowerCase && email.verified)
      }
    }

    request match {
      case _: OrganizationDomainAddRequest => managePermission.orElse(invalidDomain).orElse(emailProvider).orElse(verifiedEmail)
      case _: OrganizationDomainRemoveRequest => managePermission.orElse(invalidDomain).orElse(domainNotOwned)
      case _: OrganizationDomainPendingAddRequest => managePermission.orElse(invalidDomain).orElse(emailProvider)
      case _: OrganizationDomainSendMemberConfirmationRequest => joinViaEmailPermission.orElse(invalidDomain).orElse(domainNotOwned)
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

    ownership
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

  def sendMembershipConfirmationEmail(request: OrganizationDomainSendMemberConfirmationRequest): Either[OrganizationFail, ElectronicMail] = {
    db.readOnlyReplica(implicit s => getValidationError(request)) match {
      case Some(fail) => Left(fail)
      case None =>
        db.readWrite { implicit s =>
          userEmailAddressRepo.getByAddressAndUser(request.requesterId, request.email) match {
            case Some(userEmail) =>
              val org = orgRepo.get(request.orgId)

              val emailWithCode = userEmailAddressRepo.save(userEmail.withVerificationCode(clock.now()))
              val siteUrl = fortytwoConfig.applicationBaseUrl
              val verifyUrl = s"$siteUrl${EmailVerificationCode.verifyPath(emailWithCode.verificationCode.get, Some(Organization.publicId(org.id.get)))}"

              implicit val heimdalContext = new HeimdalContextBuilder().build
              orgAnalytics.trackOrganizationEvent(org, userRepo.get(request.requesterId), request)

              Right(postOffice.sendMail(ElectronicMail(
                from = SystemEmailAddress.NOTIFICATIONS,
                fromName = Some("Kifi"),
                to = Seq(userEmail.address),
                subject = s"Join ${org.name} on Kifi",
                htmlBody =
                  s"""
                       |Per your request, you can join <a href="www.kifi.com/${org.handle.value}" target="_blank">${org.name}</a> on Kifi by <a href=${verifyUrl} target="_blank">clicking here</a>.
                       |This will give you access to all of their team libraries, discussions, and other Kifi for Teams features.
                    """.stripMargin,
                textBody = Some(
                  s"""
                       |Per your request, you can join ${org.name} on Kifi by clicking here - ${verifyUrl}.
                       |This will give you access to all of their team libraries, discussions, and other Kifi for Teams features.
                     """.stripMargin),
                category = NotificationCategory.User.JOIN_BY_VERIFYING
              )))
            case None => Left(OrganizationFail.EMAIL_NOT_FOUND)
          }
        }
    }
  }
}
