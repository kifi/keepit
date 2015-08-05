package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Provider, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulingProperties, SequencingActor, SequencingPlugin }
import com.keepit.common.time.Clock

@ImplementedBy(classOf[OrganizationRepoImpl])
trait OrganizationRepo extends Repo[Organization] with SeqNumberFunction[Organization] {
  def allActive(implicit session: RSession): Seq[Organization]
  def getByIds(orgIds: Set[Id[Organization]])(implicit session: RSession): Map[Id[Organization], Organization]
  def deactivate(model: Organization)(implicit session: RWSession): Unit
  def getOrgByName(name: String, state: State[Organization] = OrganizationStates.ACTIVE)(implicit session: RSession): Option[Organization]
  def searchOrgsByNameFuzzy(name: String, state: State[Organization] = OrganizationStates.ACTIVE)(implicit session: RSession): Seq[Organization]
  def getPotentialOrganizationsForUser(userId: Id[User])(implicit session: RSession): Seq[Organization]
}

@Singleton
class OrganizationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    orgCache: OrganizationCache,
    libRepo: Provider[LibraryRepoImpl]) extends OrganizationRepo with DbRepo[Organization] with SeqNumberDbFunction[Organization] with Logging {

  import DBSession._
  import db.Driver.simple._

  type RepoImpl = OrganizationTable
  class OrganizationTable(tag: Tag) extends RepoTable[Organization](db, tag, "organization") with SeqNumberColumn[Organization] {

    def name = column[String]("name", O.NotNull)
    def description = column[Option[String]]("description", O.Nullable)
    def ownerId = column[Id[User]]("owner_id", O.NotNull)
    def organizationHandle = column[Option[OrganizationHandle]]("handle", O.Nullable)
    def normalizedOrganizationHandle = column[Option[OrganizationHandle]]("normalized_handle", O.Nullable)
    def site = column[Option[String]]("site", O.Nullable)
    def basePermissions = column[BasePermissions]("base_permissions", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, seq, name, description, ownerId, organizationHandle, normalizedOrganizationHandle, site, basePermissions) <> ((Organization.applyFromDbRow _).tupled, Organization.unapplyToDbRow _)
  }

  def table(tag: Tag) = new OrganizationTable(tag)
  implicit def orgId2Key(orgId: Id[Organization]): OrganizationKey = OrganizationKey(orgId)

  initTable()

  override def deleteCache(org: Organization)(implicit session: RSession) {
    orgCache.remove(org.id.get)
  }
  override def invalidateCache(org: Organization)(implicit session: RSession) {
    orgCache.set(org.id.get, org)
  }

  override def save(model: Organization)(implicit session: RWSession): Organization = {
    super.save(model.copy(seq = deferredSeqNum()))
  }

  def allActive(implicit session: RSession): Seq[Organization] = {
    val q = for { row <- rows if row.state === OrganizationStates.ACTIVE } yield row
    q.list
  }

  override def get(id: Id[Organization])(implicit session: RSession): Organization = {
    orgCache.getOrElse(OrganizationKey(id)) {
      getCompiled(id).firstOption.getOrElse(throw NotFoundException(id))
    }
  }

  // Be aware that this will return inactive organizations, so make sure you want the orgs you ask for
  def getByIds(orgIds: Set[Id[Organization]])(implicit session: RSession): Map[Id[Organization], Organization] = {
    orgCache.bulkGetOrElse(orgIds map orgId2Key) { missingKeys =>
      val q = { for { row <- rows if row.id.inSet(missingKeys.map { _.id }) } yield row }
      q.list.map { x => orgId2Key(x.id.get) -> x }.toMap
    }.map { case (key, org) => key.id -> org }
  }

  def deactivate(model: Organization)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }

  def getOrgByName(name: String, state: State[Organization] = OrganizationStates.ACTIVE)(implicit session: RSession): Option[Organization] = {
    val lowerCaseName = name.toLowerCase
    val q = for (row <- rows if row.state === state && row.name.toLowerCase === lowerCaseName) yield row
    q.firstOption
  }

  def searchOrgsByNameFuzzy(name: String, state: State[Organization] = OrganizationStates.ACTIVE)(implicit session: RSession): Seq[Organization] = {
    val lowerCaseNameEx = "%" + name.toLowerCase + "%"
    val q = for (row <- rows if row.state === state && row.name.toLowerCase.like(lowerCaseNameEx)) yield row
    q.list
  }

  def getPotentialOrganizationsForUser(userId: Id[User])(implicit session: RSession): Seq[Organization] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    val orgIds = sql"""
      select distinct organization.id from (
        select membership.organization_id from (
          select user_ip_addresses.user_id as id from (
            select distinct ip_address from user_ip_addresses where user_id = $userId
          ) as ip
            inner join user_ip_addresses on user_ip_addresses.ip_address = ip.ip_address
            where user_ip_addresses.user_id != $userId
          union
            select user_2 as id from user_connection where user_connection.user_1 = $userId
          union
            select user_1 as id from user_connection where user_connection.user_2 = $userId
        ) as user
          inner join (
            select user_id, organization_id from organization_membership
            union
              select user_id, organization_id from organization_membership_candidate
          ) as membership
          on user.id = membership.user_id
      ) as membership
        inner join organization on organization.id = membership.organization_id
        where organization.id not in (
          select organization_id from organization_membership where user_id = $userId
          union
            select organization_id from organization_membership_candidate where user_id = $userId
        ) and not exists (
          select organization_id from organization_experiment where organization_id = organization.id and experiment_type = 'fake' and state = 'active'
        );""".as[Id[Organization]].list.toSet

    getByIds(orgIds).values.toSeq
  }

}

trait OrganizationSequencingPlugin extends SequencingPlugin

class OrganizationSequencingPluginImpl @Inject() (
  override val actor: ActorInstance[OrganizationSequencingActor],
  override val scheduling: SchedulingProperties) extends OrganizationSequencingPlugin

@Singleton
class OrganizationSequenceNumberAssigner @Inject() (db: Database, repo: OrganizationRepo, airbrake: AirbrakeNotifier) extends DbSequenceAssigner[Organization](db, repo, airbrake)
class OrganizationSequencingActor @Inject() (
  assigner: OrganizationSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
