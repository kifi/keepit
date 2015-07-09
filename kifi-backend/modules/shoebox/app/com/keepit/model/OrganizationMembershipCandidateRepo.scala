package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock

@ImplementedBy(classOf[OrganizationMembershipCandidateRepoImpl])
trait OrganizationMembershipCandidateRepo extends Repo[OrganizationMembershipCandidate] {
  def getByOrgId(orgId: Id[Organization], limit: Limit, offset: Offset, states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit session: RSession): Seq[OrganizationMembershipCandidate]
  def getAllByOrgId(orgId: Id[Organization], states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit session: RSession): Seq[OrganizationMembershipCandidate]
  def getByUserId(userId: Id[User], limit: Limit, offset: Offset, states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit session: RSession): Seq[OrganizationMembershipCandidate]
  def getAllByUserId(userId: Id[User], states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit session: RSession): Seq[OrganizationMembershipCandidate]
  def deactivate(model: OrganizationMembershipCandidate)(implicit session: RWSession): Unit
}

@Singleton
class OrganizationMembershipCandidateRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends OrganizationMembershipCandidateRepo with DbRepo[OrganizationMembershipCandidate] with Logging {
  override def deleteCache(protoOrgMembership: OrganizationMembershipCandidate)(implicit session: RSession) {}
  override def invalidateCache(protoOrgMembership: OrganizationMembershipCandidate)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = OrganizationMembershipCandidateTable
  class OrganizationMembershipCandidateTable(tag: Tag) extends RepoTable[OrganizationMembershipCandidate](db, tag, "proto_organization_membership") {

    def orgId = column[Id[Organization]]("organization_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, orgId, userId) <> ((OrganizationMembershipCandidate.apply _).tupled, OrganizationMembershipCandidate.unapply)
  }

  def table(tag: Tag) = new OrganizationMembershipCandidateTable(tag)
  initTable()

  def getByOrgId(orgId: Id[Organization], limit: Limit, offset: Offset, states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit s: RSession): Seq[OrganizationMembershipCandidate] = {
    (for { row <- rows if row.orgId === orgId && row.state.inSet(states) } yield row).drop(offset.value).take(limit.value).list
  }
  def getAllByOrgId(orgId: Id[Organization], states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit s: RSession): Seq[OrganizationMembershipCandidate] = {
    getByOrgId(orgId, limit = Limit(Int.MaxValue), offset = Offset(0), states = states)
  }

  def getByUserId(userId: Id[User], limit: Limit, offset: Offset, states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit s: RSession): Seq[OrganizationMembershipCandidate] = {
    (for { row <- rows if row.userId === userId && row.state.inSet(states) } yield row).drop(offset.value).take(limit.value).list
  }
  def getAllByUserId(userId: Id[User], states: Set[State[OrganizationMembershipCandidate]] = Set(OrganizationMembershipCandidateStates.ACTIVE))(implicit s: RSession): Seq[OrganizationMembershipCandidate] = {
    getByUserId(userId, limit = Limit(Int.MaxValue), offset = Offset(0), states = states)
  }

  def deactivate(model: OrganizationMembershipCandidate)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}
