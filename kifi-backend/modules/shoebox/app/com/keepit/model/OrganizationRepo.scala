package com.keepit.model

import com.google.inject.{ Provider, ImplementedBy, Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.{ DbSequenceAssigner, Id }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{ Id, State, SequenceNumber }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import com.keepit.common.time.Clock
import org.joda.time.DateTime
import play.api.libs.json.Json

import scala.slick.jdbc.{ PositionedResult, GetResult }

@ImplementedBy(classOf[OrganizationRepoImpl])
trait OrganizationRepo extends Repo[Organization] with SeqNumberFunction[Organization] {
  def getByIds(orgIds: Set[Id[Organization]])(implicit session: RSession): Map[Id[Organization], Organization]
  def deactivate(model: Organization)(implicit session: RWSession): Unit
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
    def basePermissions = column[BasePermissions]("base_permissions", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, seq, name, description, ownerId, organizationHandle, normalizedOrganizationHandle, basePermissions) <> ((Organization.applyFromDbRow _).tupled, Organization.unapplyToDbRow _)
  }

  def table(tag: Tag) = new OrganizationTable(tag)
  implicit def orgId2Key(orgId: Id[Organization]) = OrganizationKey(orgId)

  initTable()

  implicit val getHandleResult = getResultOptionFromMapper[OrganizationHandle]
  implicit val getBasePermissionsResult = getResultFromMapper[BasePermissions]

  implicit val tableResult: GetResult[Organization] = GetResult { r: PositionedResult =>
    Organization.applyFromDbRow(
      id = r.<<[Option[Id[Organization]]],
      createdAt = r.<<[DateTime],
      updatedAt = r.<<[DateTime],
      state = r.<<[State[Organization]],
      seq = r.<<[SequenceNumber[Organization]],
      name = r.<<[String],
      description = r.<<[Option[String]],
      ownerId = r.<<[Id[User]],
      organizationHandle = r.<<[Option[OrganizationHandle]],
      normalizedOrganizationHandle = r.<<[Option[OrganizationHandle]],
      basePermissions = r.<<[BasePermissions]
    )
  }

  override def deleteCache(org: Organization)(implicit session: RSession) {
    orgCache.remove(org.id.get)
  }
  override def invalidateCache(org: Organization)(implicit session: RSession) {
    orgCache.set(org.id.get, org)
  }

  override def save(model: Organization)(implicit session: RWSession): Organization = {
    super.save(model.copy(seq = deferredSeqNum()))
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

  def getPotentialOrganizationsForUser(userId: Id[User])(implicit session: RSession): Seq[Organization] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    sql"""
          select organization.* from (
            select user_ip_addresses.user_id as id from (
              select distinct ip_address from user_ip_addresses where user_id = 1
            ) as ip
              inner join user_ip_addresses on user_ip_addresses.ip_address = ip.ip_address where user_ip_addresses.user_id != 1
            union
              select user_2 as id from user_connection where user_connection.user_1 = 1
            union
              select user_1 as id from user_connection where user_connection.user_2 = 1
          ) as user
            inner join organization_membership on organization_membership.user_id = user.id
            inner join organization on organization.id = organization_membership.organization_id
            group by organization.id;
      """.as[Organization].list
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
