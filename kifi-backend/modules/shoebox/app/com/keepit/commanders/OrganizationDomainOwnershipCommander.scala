package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.classify.{ Domain, DomainRepo }
import com.keepit.commanders.OrganizationDomainOwnershipCommander.{ DomainDidNotExist, OwnDomainSuccess, OwnDomainFailure }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model._

@ImplementedBy(classOf[OrganizationDomainOwnershipCommanderImpl])
trait OrganizationDomainOwnershipCommander {
  def getDomainsOwned(orgId: Id[Organization]): Set[Domain]
  def getOwningOrganizations(domainId: Id[Domain]): Set[Organization]
  def addDomainOwnership(orgId: Id[Organization], domainName: String): Either[OwnDomainFailure, OwnDomainSuccess]
  def removeDomainOwnership(orgId: Id[Organization], domainId: Id[Domain]): Unit
}

object OrganizationDomainOwnershipCommander {

  sealed trait OwnDomainFailure {
    def humanString: String
  }

  case class DomainDidNotExist(domainName: String) extends OwnDomainFailure {
    override def humanString = s"Domain '$domainName' did not exist!"
  }

  case class OwnDomainSuccess(domain: Domain, ownership: OrganizationDomainOwnership)

}

@Singleton
class OrganizationDomainOwnershipCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    orgDomainOwnershipRepo: OrganizationDomainOwnershipRepo,
    domainRepo: DomainRepo) extends OrganizationDomainOwnershipCommander {

  override def getDomainsOwned(orgId: Id[Organization]): Set[Domain] = {
    val domains = db.readOnlyMaster { implicit session =>
      domainRepo.getByIds(
        orgDomainOwnershipRepo.getOwnershipsForOrganization(orgId).map(ownership => ownership.domainId).toSet
      ).values.toSet
    }
    domains
  }

  override def getOwningOrganizations(domainId: Id[Domain]): Set[Organization] = {
    val orgs = db.readOnlyMaster { implicit session =>
      orgRepo.getByIds(
        orgDomainOwnershipRepo.getOwnershipsForDomain(domainId).map(ownership => ownership.organizationId).toSet
      ).values.toSet
    }
    orgs
  }

  override def addDomainOwnership(orgId: Id[Organization], domainName: String): Either[OwnDomainFailure, OwnDomainSuccess] = {
    db.readWrite { implicit session =>
      val domainOpt = domainRepo.get(domainName)
      domainOpt match {
        case None => Left(DomainDidNotExist(domainName))
        case Some(domain) =>
          val domainId = domain.id.get
          val ownership = orgDomainOwnershipRepo.getDomainOwnershipBetween(orgId, domainId, onlyState = None) match {
            case Some(own) if own.state == OrganizationDomainOwnershipStates.ACTIVE => own
            case Some(own) => orgDomainOwnershipRepo.save(own.copy(state = OrganizationDomainOwnershipStates.ACTIVE))
            case None => orgDomainOwnershipRepo.save(OrganizationDomainOwnership(organizationId = orgId, domainId = domainId))
          }
          Right(OwnDomainSuccess(domain, ownership))
      }
    }
  }

  override def removeDomainOwnership(orgId: Id[Organization], domainId: Id[Domain]): Unit = {
    db.readWrite { implicit session =>
      orgDomainOwnershipRepo.getDomainOwnershipBetween(orgId, domainId) foreach { ownership =>
        orgDomainOwnershipRepo.save(ownership.copy(state = OrganizationDomainOwnershipStates.INACTIVE))
      }
    }
  }
}