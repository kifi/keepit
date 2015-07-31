package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ SeqNumberFunction, SeqNumberDbFunction, Database, DataBaseComponent, DbRepo, Repo }
import com.keepit.common.db.{ DbSequenceAssigner, Id, State }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import com.keepit.common.time.Clock

@ImplementedBy(classOf[OrganizationMembershipCandidateRepoImpl])
trait OrganizationMembershipCandidateRepo extends Repo[OrganizationMembershipCandidate] with SeqNumberFunction[OrganizationMembershipCandidate] {
  def getByOrgId(orgId: Id[Organization], limit: Limit, offset: Offset, states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit session: RSession): Seq[OrganizationMembershipCandidate]
  def getAllByOrgId(orgId: Id[Organization], states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit session: RSession): Seq[OrganizationMembershipCandidate]
  def getByUserId(userId: Id[User], limit: Limit, offset: Offset, states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit session: RSession): Seq[OrganizationMembershipCandidate]
  def getByUserAndOrg(userId: Id[User], orgId: Id[Organization])(implicit session: RSession): Option[OrganizationMembershipCandidate]
  def getAllByUserId(userId: Id[User], states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit session: RSession): Seq[OrganizationMembershipCandidate]
  def deactivate(model: OrganizationMembershipCandidate)(implicit session: RWSession): Unit
}

@Singleton
class OrganizationMembershipCandidateRepoImpl @Inject() (
    primaryOrgForUserCache: PrimaryOrgForUserCache,
    val db: DataBaseComponent,
    val clock: Clock) extends OrganizationMembershipCandidateRepo with DbRepo[OrganizationMembershipCandidate] with SeqNumberDbFunction[OrganizationMembershipCandidate] with Logging {

  override def deleteCache(protoOrgMembership: OrganizationMembershipCandidate)(implicit session: RSession) = {
    primaryOrgForUserCache.remove(PrimaryOrgForUserKey(protoOrgMembership.userId))
  }

  override def invalidateCache(protoOrgMembership: OrganizationMembershipCandidate)(implicit session: RSession) = {
    primaryOrgForUserCache.remove(PrimaryOrgForUserKey(protoOrgMembership.userId))
  }

  import db.Driver.simple._

  type RepoImpl = OrganizationMembershipCandidateTable
  class OrganizationMembershipCandidateTable(tag: Tag) extends RepoTable[OrganizationMembershipCandidate](db, tag, "organization_membership_candidate") with SeqNumberColumn[OrganizationMembershipCandidate] {

    def orgId = column[Id[Organization]]("organization_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, orgId, userId, seq) <> ((OrganizationMembershipCandidate.apply _).tupled, OrganizationMembershipCandidate.unapply)
  }

  def table(tag: Tag) = new OrganizationMembershipCandidateTable(tag)
  initTable()

  override def save(model: OrganizationMembershipCandidate)(implicit session: RWSession): OrganizationMembershipCandidate = {
    super.save(model.copy(seq = deferredSeqNum()))
  }

  def getByOrgId(orgId: Id[Organization], limit: Limit, offset: Offset, states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit s: RSession): Seq[OrganizationMembershipCandidate] = {
    (for { row <- rows if row.orgId === orgId && row.state.inSet(states) } yield row).drop(offset.value).take(limit.value).list
  }
  def getAllByOrgId(orgId: Id[Organization], states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit s: RSession): Seq[OrganizationMembershipCandidate] = {
    getByOrgId(orgId, limit = Limit(Int.MaxValue), offset = Offset(0), states = states)
  }

  def getByUserId(userId: Id[User], limit: Limit, offset: Offset, states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit s: RSession): Seq[OrganizationMembershipCandidate] = {
    (for { row <- rows if row.userId === userId && row.state.inSet(states) } yield row).drop(offset.value).take(limit.value).list
  }

  def getByUserAndOrg(userId: Id[User], orgId: Id[Organization])(implicit session: RSession): Option[OrganizationMembershipCandidate] = {
    (for { row <- rows if row.userId === userId && row.orgId === orgId } yield row).firstOption
  }

  def getAllByUserId(userId: Id[User], states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit s: RSession): Seq[OrganizationMembershipCandidate] = {
    getByUserId(userId, limit = Limit(Int.MaxValue), offset = Offset(0), states = states)
  }

  def deactivate(model: OrganizationMembershipCandidate)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}

trait OrganizationMembershipCandidateSequencingPlugin extends SequencingPlugin

class OrganizationMembershipCandidateSequencingPluginImpl @Inject() (
  override val actor: ActorInstance[OrganizationMembershipCandidateSequencingActor],
  override val scheduling: SchedulingProperties) extends OrganizationMembershipCandidateSequencingPlugin

@Singleton
class OrganizationMembershipCandidateSequenceNumberAssigner @Inject() (db: Database, repo: OrganizationMembershipCandidateRepo, airbrake: AirbrakeNotifier) extends DbSequenceAssigner[OrganizationMembershipCandidate](db, repo, airbrake)
class OrganizationMembershipCandidateSequencingActor @Inject() (
  assigner: OrganizationMembershipCandidateSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
