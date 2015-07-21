package com.keepit.model

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.classify.Domain
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._

import scala.concurrent.duration.Duration

@ImplementedBy(classOf[OrganizationDomainOwnershipRepoImpl])
trait OrganizationDomainOwnershipRepo extends Repo[OrganizationDomainOwnership] with SeqNumberFunction[OrganizationDomainOwnership] {
  def getDomainOwnershipBetween(organization: Id[Organization], domain: Id[Domain], onlyState: Option[State[OrganizationDomainOwnership]] = Option(OrganizationDomainOwnershipStates.ACTIVE))(implicit session: RSession): Option[OrganizationDomainOwnership]
  def getOwnershipsForOrganization(organization: Id[Organization])(implicit session: RSession): Seq[OrganizationDomainOwnership]
  def getOwnershipsForDomain(domain: Id[Domain])(implicit session: RSession): Seq[OrganizationDomainOwnership]
}

@Singleton
class OrganizationDomainOwnershipRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val cache: OrganizationDomainOwnershipAllCache) extends DbRepo[OrganizationDomainOwnership] with OrganizationDomainOwnershipRepo with SeqNumberDbFunction[OrganizationDomainOwnership] {

  import db.Driver.simple._

  type RepoImpl = OrganizationDomainOwnershipTable

  class OrganizationDomainOwnershipTable(tag: Tag)
      extends RepoTable[OrganizationDomainOwnership](db, tag, "organization_domain_ownership")
      with SeqNumberColumn[OrganizationDomainOwnership] {

    def organizationId = column[Id[Organization]]("organization_id", O.NotNull)

    def domainId = column[Id[Domain]]("domain_id", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, seq, organizationId, domainId) <> ((OrganizationDomainOwnership.apply _).tupled, OrganizationDomainOwnership.unapply)
  }

  def table(tag: Tag) = new OrganizationDomainOwnershipTable(tag)

  initTable()

  override def save(model: OrganizationDomainOwnership)(implicit session: RWSession): OrganizationDomainOwnership = {
    super.save(model.copy(seq = deferredSeqNum()))
  }

  override def invalidateCache(model: OrganizationDomainOwnership)(implicit session: RSession): Unit = {}

  override def deleteCache(model: OrganizationDomainOwnership)(implicit session: RSession): Unit = {}

  override def getDomainOwnershipBetween(organization: Id[Organization], domain: Id[Domain], onlyState: Option[State[OrganizationDomainOwnership]] = Option(OrganizationDomainOwnershipStates.ACTIVE))(implicit session: RSession): Option[OrganizationDomainOwnership] = {
    val query =
      if (onlyState.isDefined) {
        for { row <- rows if row.organizationId === organization && row.domainId === domain && row.state === onlyState } yield row
      } else {
        for { row <- rows if row.organizationId === organization && row.domainId === domain } yield row
      }
    query.firstOption
  }

  override def getOwnershipsForOrganization(organization: Id[Organization])(implicit session: RSession): Seq[OrganizationDomainOwnership] = {
    val query = for { row <- rows if row.organizationId === organization && row.state === OrganizationDomainOwnershipStates.ACTIVE } yield row
    query.list
  }

  override def getOwnershipsForDomain(domain: Id[Domain])(implicit session: RSession): Seq[OrganizationDomainOwnership] = {
    val query = for { row <- rows if row.domainId === domain && row.state === OrganizationDomainOwnershipStates.ACTIVE } yield row
    query.list
  }

}

case class OrganizationDomainOwnershipAllKey() extends Key[Seq[OrganizationDomainOwnership]] {
  override val version = 1
  val namespace = "organization_domain_ownership_all"
  def toKey(): String = "all"
}

class OrganizationDomainOwnershipAllCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[OrganizationDomainOwnershipAllKey, Seq[OrganizationDomainOwnership]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
