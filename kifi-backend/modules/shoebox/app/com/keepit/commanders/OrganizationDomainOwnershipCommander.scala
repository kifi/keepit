package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.classify.{ NormalizedHostname, Domain, DomainRepo }
import com.keepit.commanders.OrganizationDomainOwnershipCommander.{ DomainIsEmailProvider, InvalidDomainName, DomainAlreadyOwned, DomainDidNotExist, OwnDomainSuccess, OwnDomainFailure }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.model._
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext }

@ImplementedBy(classOf[OrganizationDomainOwnershipCommanderImpl])
trait OrganizationDomainOwnershipCommander {
  def getDomainsOwned(orgId: Id[Organization]): Set[Domain]
  def getOwningOrganizations(domainHostname: NormalizedHostname): Set[Id[Organization]]
  def addDomainOwnership(orgId: Id[Organization], domainName: String): Either[OwnDomainFailure, OwnDomainSuccess]
  def removeDomainOwnership(orgId: Id[Organization], domainHostname: String): Option[OwnDomainFailure]
  def getSharedEmails(userId: Id[User], orgId: Id[Organization]): Set[EmailAddress]
  def getSharedEmailsHelper(userId: Id[User], orgId: Id[Organization])(implicit session: RSession): Set[EmailAddress]
  def hideOrganizationForUser(userId: Id[User], orgId: Id[Organization]): Unit
}

object OrganizationDomainOwnershipCommander {

  sealed trait OwnDomainFailure {
    def humanString: String
  }

  case class InvalidDomainName(domainName: String) extends OwnDomainFailure {
    override def humanString = s"Domain '$domainName' is invalid!"
  }

  case class DomainDidNotExist(domainName: String) extends OwnDomainFailure {
    override def humanString = s"Domain '$domainName' did not exist!"
  }

  case class DomainIsEmailProvider(domainName: String) extends OwnDomainFailure {
    override def humanString = s"Domain '$domainName' is an email provider!"
  }

  case class DomainAlreadyOwned(domainName: String) extends OwnDomainFailure {
    override def humanString = s"Domain $domainName is already owned by an organization!"
  }

  case class OwnDomainSuccess(domain: Domain, ownership: OrganizationDomainOwnership)
}

@Singleton
class OrganizationDomainOwnershipCommanderImpl @Inject() (
    db: Database,
    orgDomainOwnershipRepo: OrganizationDomainOwnershipRepo,
    orgConfigurationRepo: OrganizationConfigurationRepo,
    domainRepo: DomainRepo,
    userEmailAddressRepo: UserEmailAddressRepo,
    userEmailAddressCommander: UserEmailAddressCommander,
    orgMembershipRepo: OrganizationMembershipRepo,
    userValueRepo: UserValueRepo,
    implicit val executionContext: ScalaExecutionContext) extends OrganizationDomainOwnershipCommander with Logging {

  override def getDomainsOwned(orgId: Id[Organization]): Set[Domain] = {
    val domains = db.readOnlyMaster { implicit session =>
      domainRepo.getByIds(
        orgDomainOwnershipRepo.getOwnershipsForOrganization(orgId).map(ownership => domainRepo.get(ownership.normalizedHostname).flatMap(_.id)).collect {
          case Some(id) => id
        }.toSet
      ).values.toSet
    }
    domains
  }

  override def getOwningOrganizations(domainHostname: NormalizedHostname): Set[Id[Organization]] = db.readOnlyMaster { implicit session =>
    orgDomainOwnershipRepo.getOwnershipsForDomain(domainHostname).map(_.organizationId)
  }

  override def addDomainOwnership(orgId: Id[Organization], domainName: String): Either[OwnDomainFailure, OwnDomainSuccess] = {
    db.readWrite { implicit session =>
      NormalizedHostname.fromHostname(domainName) match {
        case None => Left(InvalidDomainName(domainName))
        case Some(normalizedHostname) => {
          domainRepo.intern(normalizedHostname) match {
            case domain if domain.isEmailProvider => Left(DomainIsEmailProvider(domainName))
            case domain => {
              val ownership: OrganizationDomainOwnership = orgDomainOwnershipRepo.getDomainOwnershipBetween(orgId, domain.hostname, excludeState = None) match {
                case Some(activeOwnership) if activeOwnership.isActive => activeOwnership
                case inactiveOpt => orgDomainOwnershipRepo.save(OrganizationDomainOwnership(id = inactiveOpt.map(_.id.get), organizationId = orgId, normalizedHostname = normalizedHostname))
              }
              db.readWrite { implicit session =>
                val canVerifyToJoin = orgConfigurationRepo.getByOrgId(ownership.organizationId).settings.settingFor(Feature.JoinByVerifying).contains(FeatureSetting.NONMEMBERS)
                if (canVerifyToJoin) sendVerificationEmailsToAllPotentialMembers(ownership)
              }
              Right(OwnDomainSuccess(domain, ownership))
            }
          }
        }
      }
    }
  }

  private def sendVerificationEmailsToAllPotentialMembers(ownership: OrganizationDomainOwnership)(implicit session: RWSession): Unit = {
    val orgMembers = orgMembershipRepo.getAllByOrgId(ownership.organizationId)
    val usersToEmail = userEmailAddressRepo.getByDomain(ownership.normalizedHostname)
      .filter(userEmail => !orgMembers.exists(_.userId == userEmail.userId))
      .filter(userEmail => !userValueRepo.getValue(userEmail.userId, UserValues.hideEmailDomainOrganizations).as[Set[Id[Organization]]].contains(ownership.organizationId))
    usersToEmail.foreach(userEmailAddressCommander.sendVerificationEmailHelper)
  }

  override def removeDomainOwnership(orgId: Id[Organization], domainHostname: String): Option[OwnDomainFailure] = {
    NormalizedHostname.fromHostname(domainHostname) match {
      case Some(normalizedHostname) =>
        db.readWrite { implicit session =>
          orgDomainOwnershipRepo.getDomainOwnershipBetween(orgId, normalizedHostname).map { ownership =>
            orgDomainOwnershipRepo.save(ownership.copy(state = OrganizationDomainOwnershipStates.INACTIVE))
          } match {
            case Some(saved) => None
            case None => Some(DomainDidNotExist(domainHostname))
          }
        }
      case None => Some(InvalidDomainName(domainHostname))
    }
  }

  override def getSharedEmails(userId: Id[User], orgId: Id[Organization]): Set[EmailAddress] = db.readOnlyReplica { implicit session =>
    getSharedEmailsHelper(userId, orgId)
  }

  override def getSharedEmailsHelper(userId: Id[User], orgId: Id[Organization])(implicit session: RSession): Set[EmailAddress] = {
    val emails = userEmailAddressRepo.getAllByUser(userId).map(_.address)
    val orgDomains = orgDomainOwnershipRepo.getOwnershipsForOrganization(orgId).map(_.normalizedHostname).toSet
    emails.filter { email =>
      val hostnameOpt = NormalizedHostname.fromHostname(EmailAddress.getHostname(email))
      hostnameOpt.exists(orgDomains.contains)
    }.toSet
  }

  override def hideOrganizationForUser(userId: Id[User], orgId: Id[Organization]): Unit = {
    db.readWrite { implicit session =>
      val newOrgsToIgnore = userValueRepo.getValue(userId, UserValues.hideEmailDomainOrganizations).as[Set[Id[Organization]]] + orgId
      userValueRepo.setValue(userId, UserValueName.HIDE_EMAIL_DOMAIN_ORGANIZATIONS, Json.toJson(newOrgsToIgnore))
    }
  }
}
