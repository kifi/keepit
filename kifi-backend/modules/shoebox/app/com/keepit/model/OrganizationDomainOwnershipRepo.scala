package com.keepit.model

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.classify.{ NormalizedHostname, Domain }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.AccessLog
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import com.keepit.common.time._

import scala.concurrent.duration.Duration

@ImplementedBy(classOf[OrganizationDomainOwnershipRepoImpl])
trait OrganizationDomainOwnershipRepo extends Repo[OrganizationDomainOwnership] with SeqNumberFunction[OrganizationDomainOwnership] {
  def getDomainOwnershipBetween(organization: Id[Organization], domainHostname: NormalizedHostname, excludeState: Option[State[OrganizationDomainOwnership]] = Some(OrganizationDomainOwnershipStates.INACTIVE))(implicit session: RSession): Option[OrganizationDomainOwnership]
  def getOwnershipsForOrganization(organization: Id[Organization], excludeState: Option[State[OrganizationDomainOwnership]] = Some(OrganizationDomainOwnershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationDomainOwnership]
  def getOwnershipForDomain(domainHostname: NormalizedHostname, excludeState: Option[State[OrganizationDomainOwnership]] = Some(OrganizationDomainOwnershipStates.INACTIVE))(implicit session: RSession): Option[OrganizationDomainOwnership]
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

    def domainHostname = column[NormalizedHostname]("domain_hostname", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, seq, organizationId, domainHostname) <> ((OrganizationDomainOwnership.apply _).tupled, OrganizationDomainOwnership.unapply)
  }

  def table(tag: Tag) = new OrganizationDomainOwnershipTable(tag)

  initTable()

  override def save(model: OrganizationDomainOwnership)(implicit session: RWSession): OrganizationDomainOwnership = {
    super.save(model.copy(seq = deferredSeqNum()))
  }

  override def invalidateCache(model: OrganizationDomainOwnership)(implicit session: RSession): Unit = {}

  override def deleteCache(model: OrganizationDomainOwnership)(implicit session: RSession): Unit = {}

  override def getDomainOwnershipBetween(organization: Id[Organization], domainHostname: NormalizedHostname, excludeState: Option[State[OrganizationDomainOwnership]] = Some(OrganizationDomainOwnershipStates.INACTIVE))(implicit session: RSession): Option[OrganizationDomainOwnership] = {
    (for { row <- rows if row.organizationId === organization && row.domainHostname === domainHostname && row.state =!= excludeState.orNull } yield row).firstOption
  }

  override def getOwnershipsForOrganization(organization: Id[Organization], excludeState: Option[State[OrganizationDomainOwnership]] = Some(OrganizationDomainOwnershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationDomainOwnership] = {
    (for { row <- rows if row.organizationId === organization && row.state =!= excludeState.orNull } yield row).list
  }

  override def getOwnershipForDomain(domainHostname: NormalizedHostname, excludeState: Option[State[OrganizationDomainOwnership]] = Some(OrganizationDomainOwnershipStates.INACTIVE))(implicit session: RSession): Option[OrganizationDomainOwnership] = {
    (for { row <- rows if row.domainHostname === domainHostname && row.state =!= excludeState.orNull } yield row).firstOption
  }

}

case class OrganizationDomainOwnershipAllKey() extends Key[Seq[OrganizationDomainOwnership]] {
  override val version = 1
  val namespace = "organization_domain_ownership_all"
  def toKey(): String = "all"
}

class OrganizationDomainOwnershipAllCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[OrganizationDomainOwnershipAllKey, Seq[OrganizationDomainOwnership]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

trait OrganizationDomainOwnershipSequencingPlugin extends SequencingPlugin

class OrganizationDomainOwnershipSequencingPluginImpl @Inject() (
  override val actor: ActorInstance[OrganizationDomainOwnershipSequencingActor],
  override val scheduling: SchedulingProperties) extends OrganizationDomainOwnershipSequencingPlugin

@Singleton
class OrganizationDomainOwnershipSequenceNumberAssigner @Inject() (db: Database, repo: OrganizationDomainOwnershipRepo, airbrake: AirbrakeNotifier) extends DbSequenceAssigner[OrganizationDomainOwnership](db, repo, airbrake)
class OrganizationDomainOwnershipSequencingActor @Inject() (
  assigner: OrganizationDomainOwnershipSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
