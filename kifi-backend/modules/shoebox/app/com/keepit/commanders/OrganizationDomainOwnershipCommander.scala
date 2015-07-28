package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.classify.{ Domain, DomainRepo }
import com.keepit.commanders.OrganizationDomainOwnershipCommander.{ DomainDidNotExist, OwnDomainSuccess, OwnDomainFailure }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model._
import org.joda.time.DateTime

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext, Future }

@ImplementedBy(classOf[OrganizationDomainOwnershipCommanderImpl])
trait OrganizationDomainOwnershipCommander {
  def getDomainsOwned(orgId: Id[Organization]): Set[Domain]
  def getOwningOrganization(domainHostname: String): Option[Organization]
  def addDomainOwnership(orgId: Id[Organization], domainName: String): Either[OwnDomainFailure, OwnDomainSuccess]
  def removeDomainOwnership(orgId: Id[Organization], domainHostname: String): Unit
}

object OrganizationDomainOwnershipCommander {

  sealed trait OwnDomainFailure {
    def humanString: String
  }

  case class DomainDidNotExist(domainName: String) extends OwnDomainFailure {
    override def humanString = s"Domain '$domainName' did not exist!"
  }

  case class DomainAlreadyClaimed(domainName: String) extends OwnDomainFailure {
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
    implicit val executionContext: ScalaExecutionContext) extends OrganizationDomainOwnershipCommander with Logging {

  override def getDomainsOwned(orgId: Id[Organization]): Set[Domain] = {
    val domains = db.readOnlyMaster { implicit session =>
      domainRepo.getByIds(
        orgDomainOwnershipRepo.getOwnershipsForOrganization(orgId).map(ownership => domainRepo.get(ownership.domainHostname).flatMap(_.id)).collect {
          case Some(id) => id
        }.toSet
      ).values.toSet
    }
    domains
  }

  override def getOwningOrganization(domainHostname: String): Option[Organization] = {
    val org = db.readOnlyMaster { implicit session =>
      orgDomainOwnershipRepo.getOwnershipForDomain(domainHostname).map { ownership =>
        orgRepo.get(ownership.organizationId)
      }
    }
    org
  }

  override def addDomainOwnership(orgId: Id[Organization], domainName: String): Either[OwnDomainFailure, OwnDomainSuccess] = {
    db.readWrite { implicit session =>
      val domainOpt = domainRepo.get(domainName)
      domainOpt match {
        case None => Left(DomainDidNotExist(domainName))
        case Some(domain) =>
          val domainHostname = domain.hostname
          val ownership = orgDomainOwnershipRepo.getDomainOwnershipBetween(orgId, domainHostname, onlyState = None) match {
            case Some(own) if own.state == OrganizationDomainOwnershipStates.ACTIVE => own
            case Some(own) => orgDomainOwnershipRepo.save(own.copy(state = OrganizationDomainOwnershipStates.ACTIVE))
            case None => orgDomainOwnershipRepo.save(OrganizationDomainOwnership(organizationId = orgId, domainHostname = domainHostname))
          }
          Right(OwnDomainSuccess(domain, ownership))
      }
    }
  }

  override def removeDomainOwnership(orgId: Id[Organization], domainHostname: String): Unit = {
    db.readWrite { implicit session =>
      orgDomainOwnershipRepo.getDomainOwnershipBetween(orgId, domainHostname) foreach { ownership =>
        orgDomainOwnershipRepo.save(ownership.copy(state = OrganizationDomainOwnershipStates.INACTIVE))
      }
    }
  }
}
