package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.classify.{ NormalizedHostname, Domain, DomainRepo }
import com.keepit.commanders.OrganizationDomainOwnershipCommander.{ InvalidDomainName, DomainAlreadyOwned, DomainDidNotExist, OwnDomainSuccess, OwnDomainFailure }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.model._

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext }

@ImplementedBy(classOf[OrganizationDomainOwnershipCommanderImpl])
trait OrganizationDomainOwnershipCommander {
  def getDomainsOwned(orgId: Id[Organization]): Set[Domain]
  def getOwningOrganization(domainHostname: NormalizedHostname): Option[Organization]
  def addDomainOwnership(orgId: Id[Organization], domainName: String): Either[OwnDomainFailure, OwnDomainSuccess]
  def removeDomainOwnership(orgId: Id[Organization], domainHostname: String): Option[OwnDomainFailure]
  def getSharedEmails(userId: Id[User], orgId: Id[Organization]): Set[EmailAddress]
  def getSharedEmailsHelper(userId: Id[User], orgId: Id[Organization])(implicit session: RSession): Set[EmailAddress]
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

  case class DomainAlreadyOwned(domainName: String) extends OwnDomainFailure {
    override def humanString = s"Domain $domainName is already owned by an organization!"
  }

  case class OwnDomainSuccess(domain: Domain, ownership: OrganizationDomainOwnership)
}

@Singleton
class OrganizationDomainOwnershipCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    orgDomainOwnershipRepo: OrganizationDomainOwnershipRepo,
    domainRepo: DomainRepo,
    userEmailAddressRepo: UserEmailAddressRepo,
    userEmailAddressCommander: UserEmailAddressCommander,
    orgMembershipRepo: OrganizationMembershipRepo,
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

  override def getOwningOrganization(domainHostname: NormalizedHostname): Option[Organization] = {
    db.readOnlyMaster { implicit session =>
      orgDomainOwnershipRepo.getOwnershipForDomain(domainHostname).map { ownership =>
        orgRepo.get(ownership.organizationId)
      }
    }
  }

  override def addDomainOwnership(orgId: Id[Organization], domainName: String): Either[OwnDomainFailure, OwnDomainSuccess] = {
    db.readWrite { implicit session =>
      NormalizedHostname.fromHostname(domainName).fold[Either[OwnDomainFailure, OwnDomainSuccess]](ifEmpty = Left(InvalidDomainName(domainName))) { normalizedHostname =>
        domainRepo.get(normalizedHostname).filter(!_.isEmailProvider).fold[Either[OwnDomainFailure, OwnDomainSuccess]](ifEmpty = Left(DomainDidNotExist(domainName))) { domain =>
          orgDomainOwnershipRepo.getOwnershipForDomain(domain.hostname, excludeState = None).fold[Either[OwnDomainFailure, OwnDomainSuccess]](
            ifEmpty = Right(OwnDomainSuccess(domain, orgDomainOwnershipRepo.save(orgDomainOwnershipRepo.save(OrganizationDomainOwnership(organizationId = orgId, normalizedHostname = domain.hostname)))))
          ) {
              case ownership if ownership.state == OrganizationDomainOwnershipStates.INACTIVE =>
                Right(OwnDomainSuccess(domain, orgDomainOwnershipRepo.save(ownership.copy(state = OrganizationDomainOwnershipStates.ACTIVE))))
              case ownership if ownership.organizationId != orgId => Left(DomainAlreadyOwned(domainName))
              case ownership => Right(OwnDomainSuccess(domain, ownership))
            }
        }
      }
    } match {
      case Right(success) =>
        db.readWrite { implicit session =>
          val orgMembers = orgMembershipRepo.getAllByOrgId(success.ownership.organizationId)
          val usersToEmail = userEmailAddressRepo.getByDomain(success.ownership.normalizedHostname)
            .filter(userEmail => !orgMembers.exists(_.userId == userEmail.userId))
          usersToEmail.foreach(userEmailAddressCommander.sendVerificationEmailHelper)
        }
        Right(success)
      case fail => fail
    }

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
}
