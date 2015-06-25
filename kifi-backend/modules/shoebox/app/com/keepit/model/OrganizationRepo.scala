package com.keepit.model

import com.google.inject.{ Provider, ImplementedBy, Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.{ DbSequenceAssigner, Id }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{ Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import com.keepit.common.time.Clock
import play.api.libs.json.Json

@ImplementedBy(classOf[OrganizationRepoImpl])
trait OrganizationRepo extends Repo[Organization] with SeqNumberFunction[Organization] {
  def updateName(organizationId: Id[Organization], name: String): Organization = ???
  def updateDescription(organizationId: Id[Organization], description: String): Organization = ???
  def getByIds(orgIds: Set[Id[Organization]])(implicit session: RSession): Map[Id[Organization], Organization]
  def deactivate(model: Organization)(implicit session: RWSession): Unit
}

@Singleton
class OrganizationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    orgCache: OrganizationCache,
    libRepo: Provider[LibraryRepoImpl]) extends OrganizationRepo with DbRepo[Organization] with SeqNumberDbFunction[Organization] with Logging {

  import db.Driver.simple._

  type RepoImpl = OrganizationTable
  class OrganizationTable(tag: Tag) extends RepoTable[Organization](db, tag, "organization") with SeqNumberColumn[Organization] {
    implicit val basePermissionsMapper = MappedColumnType.base[BasePermissions, String](
      { basePermissions => Json.stringify(Json.toJson(basePermissions)) },
      { str => Json.parse(str).as[BasePermissions] }
    )

    def name = column[String]("name", O.NotNull)
    def description = column[Option[String]]("description", O.Nullable)
    def ownerId = column[Id[User]]("owner_id", O.NotNull)
    def organizationHandle = column[Option[OrganizationHandle]]("handle", O.Nullable)
    def normalizedOrganizationHandle = column[Option[OrganizationHandle]]("normalized_handle", O.Nullable)
    def basePermissions = column[BasePermissions]("base_permissions", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, seq, name, description, ownerId, organizationHandle, normalizedOrganizationHandle, basePermissions) <> ((Organization.applyFromDbRow _).tupled, Organization.unapplyToDbRow _)
  }

  def table(tag: Tag) = new OrganizationTable(tag)
  implicit def orgId2Key(orgId: Id[Organization]) = OrganizationKey(orgId)

  initTable()

  override def deleteCache(org: Organization)(implicit session: RSession) {
    orgCache.remove(org.id.get)
  }
  override def invalidateCache(org: Organization)(implicit session: RSession) {
    orgCache.set(org.id.get, org)
  }

  def getByIds(orgIds: Set[Id[Organization]])(implicit session: RSession): Map[Id[Organization], Organization] = {
    orgCache.bulkGetOrElse(orgIds map orgId2Key) { missingKeys =>
      val q = { for { row <- rows if row.id.inSet(missingKeys.map { _.id }.toSet) && row.state === OrganizationStates.ACTIVE } yield row }
      q.list.map { x => (orgId2Key(x.id.get) -> x) }.toMap
    }.map { case (key, org) => key.id -> org }.toMap
  }

  def deactivate(model: Organization)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
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
