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

@ImplementedBy(classOf[ProtoOrganizationRepoImpl])
trait ProtoOrganizationRepo extends Repo[ProtoOrganization] with SeqNumberFunction[ProtoOrganization] {
  def deactivate(model: ProtoOrganization)(implicit session: RWSession): Unit
}

@Singleton
class ProtoOrganizationRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock) extends ProtoOrganizationRepo with DbRepo[ProtoOrganization] with SeqNumberDbFunction[ProtoOrganization] with Logging {

  import db.Driver.simple._

  type RepoImpl = ProtoOrganizationTable
  class ProtoOrganizationTable(tag: Tag) extends RepoTable[ProtoOrganization](db, tag, "organization") with SeqNumberColumn[ProtoOrganization] {

    def name = column[String]("name", O.NotNull)
    def description = column[Option[String]]("description", O.Nullable)
    def ownerId = column[Id[User]]("owner_id", O.NotNull)
    def basePermissions = column[BasePermissions]("base_permissions", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, seq, name, description, ownerId, members, inviteeEmails) <> ((ProtoOrganization.applyFromDbRow _).tupled, ProtoOrganization.unapplyToDbRow _)
  }

  def table(tag: Tag) = new ProtoOrganizationTable(tag)
  initTable()

  override def deleteCache(org: Organization)(implicit session: RSession) {}
  override def invalidateCache(org: Organization)(implicit session: RSession) {}

  def deactivate(model: ProtoOrganization)(implicit session: RWSession): Unit = {
    save(model.withState(ProtoOrganizationStates.INACTIVE))
  }
}

trait ProtoOrganizationSequencingPlugin extends SequencingPlugin
class ProtoOrganizationSequencingPluginImpl @Inject() (
  override val actor: ActorInstance[ProtoOrganizationSequencingActor],
  override val scheduling: SchedulingProperties) extends ProtoOrganizationSequencingPlugin

@Singleton
class ProtoOrganizationSequenceNumberAssigner @Inject() (db: Database, repo: ProtoOrganizationRepo, airbrake: AirbrakeNotifier) extends DbSequenceAssigner[ProtoOrganization](db, repo, airbrake)
class ProtoOrganizationSequencingActor @Inject() (
                                              assigner: ProtoOrganizationSequenceNumberAssigner,
                                              airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
