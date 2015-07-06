package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.db.{ Id, State }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time.Clock

@ImplementedBy(classOf[ProtoOrganizationMembershipRepoImpl])
trait ProtoOrganizationMembershipRepo extends Repo[ProtoOrganizationMembership] {
  def getByOrgId(orgId: Id[Organization], limit: Limit, offset: Offset, states: Set[State[ProtoOrganizationMembership]] = Set(ProtoOrganizationMembershipStates.ACTIVE))(implicit session: RSession): Seq[ProtoOrganizationMembership]
  def getAllByOrgId(orgId: Id[Organization], states: Set[State[ProtoOrganizationMembership]] = Set(ProtoOrganizationMembershipStates.ACTIVE))(implicit session: RSession): Seq[ProtoOrganizationMembership]
  def deactivate(model: ProtoOrganizationMembership)(implicit session: RWSession): Unit
}

@Singleton
class ProtoOrganizationMembershipRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends ProtoOrganizationMembershipRepo with DbRepo[ProtoOrganizationMembership] with Logging {
  override def deleteCache(protoOrgMembership: ProtoOrganizationMembership)(implicit session: RSession) {}
  override def invalidateCache(protoOrgMembership: ProtoOrganizationMembership)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = ProtoOrganizationMembershipTable
  class ProtoOrganizationMembershipTable(tag: Tag) extends RepoTable[ProtoOrganizationMembership](db, tag, "proto_organization_membership") {

    def orgId = column[Id[Organization]]("organization_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, orgId, userId) <> ((ProtoOrganizationMembership.apply _).tupled, ProtoOrganizationMembership.unapply)
  }

  def table(tag: Tag) = new ProtoOrganizationMembershipTable(tag)
  initTable()

  def getByOrgId(orgId: Id[Organization], limit: Limit, offset: Offset, states: Set[State[ProtoOrganizationMembership]] = Set(ProtoOrganizationMembershipStates.ACTIVE))(implicit s: RSession): Seq[ProtoOrganizationMembership] = {
    (for { row <- rows if row.orgId === orgId && row.state.inSet(states) } yield row).drop(offset.value).take(limit.value).list
  }
  def getAllByOrgId(orgId: Id[Organization], states: Set[State[ProtoOrganizationMembership]] = Set(ProtoOrganizationMembershipStates.ACTIVE))(implicit s: RSession): Seq[ProtoOrganizationMembership] = {
    getByOrgId(orgId, limit = Limit(Int.MaxValue), offset = Offset(0), states = states)
  }

  def deactivate(model: ProtoOrganizationMembership)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}
