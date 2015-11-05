package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ Id, State }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock

@ImplementedBy(classOf[OrganizationMembershipPokeRepoImpl])
trait OrganizationMembershipPokeRepo extends Repo[OrganizationMembershipPoke] {
  def getByUserId(userId: Id[User], limit: Limit, offset: Offset, excludeStates: Set[State[OrganizationMembershipPoke]] = Set(OrganizationMembershipPokeStates.INACTIVE))(implicit session: RSession): Set[OrganizationMembershipPoke]
  def getAllByUserId(userId: Id[User], excludeStates: Set[State[OrganizationMembershipPoke]] = Set(OrganizationMembershipPokeStates.INACTIVE))(implicit session: RSession): Set[OrganizationMembershipPoke]
  def getByOrgId(orgId: Id[Organization], limit: Limit, offset: Offset, excludeStates: Set[State[OrganizationMembershipPoke]] = Set(OrganizationMembershipPokeStates.INACTIVE))(implicit session: RSession): Set[OrganizationMembershipPoke]
  def getAllByOrgId(orgId: Id[Organization], excludeStates: Set[State[OrganizationMembershipPoke]] = Set(OrganizationMembershipPokeStates.INACTIVE))(implicit session: RSession): Set[OrganizationMembershipPoke]
  def getByOrgIdAndUserId(orgId: Id[Organization], userId: Id[User], excludeStates: Set[State[OrganizationMembershipPoke]] = Set(OrganizationMembershipPokeStates.INACTIVE))(implicit session: RSession): Option[OrganizationMembershipPoke]
  def getByOrgIdAndUserIds(orgId: Id[Organization], userIds: Set[Id[User]], excludeStates: Set[State[OrganizationMembershipPoke]] = Set(OrganizationMembershipPokeStates.INACTIVE))(implicit session: RSession): Set[OrganizationMembershipPoke]
  def countByOrgId(orgId: Id[Organization], excludeStates: Set[State[OrganizationMembershipPoke]] = Set(OrganizationMembershipPokeStates.INACTIVE))(implicit session: RSession): Int
  def deactivate(model: OrganizationMembershipPoke)(implicit session: RWSession): Unit
}

@Singleton
class OrganizationMembershipPokeRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends OrganizationMembershipPokeRepo with DbRepo[OrganizationMembershipPoke] with Logging {
  override def deleteCache(orgMember: OrganizationMembershipPoke)(implicit session: RSession) {}
  override def invalidateCache(orgMember: OrganizationMembershipPoke)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = OrganizationMembershipPokeTable
  class OrganizationMembershipPokeTable(tag: Tag) extends RepoTable[OrganizationMembershipPoke](db, tag, "organization_membership_poke") {
    def organizationId = column[Id[Organization]]("organization_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, organizationId, userId) <> ((OrganizationMembershipPoke.apply _).tupled, OrganizationMembershipPoke.unapply)
  }

  def table(tag: Tag) = new OrganizationMembershipPokeTable(tag)
  initTable()

  def getByUserId(userId: Id[User], limit: Limit, offset: Offset, excludeStates: Set[State[OrganizationMembershipPoke]] = Set(OrganizationMembershipPokeStates.INACTIVE))(implicit session: RSession): Set[OrganizationMembershipPoke] = {
    (for (row <- rows if row.userId === userId && !row.state.inSet(excludeStates)) yield row).drop(offset.value).take(limit.value).list.toSet
  }
  def getAllByUserId(userId: Id[User], excludeStates: Set[State[OrganizationMembershipPoke]] = Set(OrganizationMembershipPokeStates.INACTIVE))(implicit session: RSession): Set[OrganizationMembershipPoke] = {
    getByUserId(userId, Limit(Int.MaxValue), Offset(0), excludeStates)
  }
  def getByOrgId(orgId: Id[Organization], limit: Limit, offset: Offset, excludeStates: Set[State[OrganizationMembershipPoke]] = Set(OrganizationMembershipPokeStates.INACTIVE))(implicit session: RSession): Set[OrganizationMembershipPoke] = {
    (for (row <- rows if row.organizationId === orgId && !row.state.inSet(excludeStates)) yield row).drop(offset.value).take(limit.value).list.toSet
  }
  def getAllByOrgId(orgId: Id[Organization], excludeStates: Set[State[OrganizationMembershipPoke]] = Set(OrganizationMembershipPokeStates.INACTIVE))(implicit session: RSession): Set[OrganizationMembershipPoke] = {
    getByOrgId(orgId, Limit(Int.MaxValue), Offset(0), excludeStates)
  }
  def getByOrgIdAndUserId(orgId: Id[Organization], userId: Id[User], excludeStates: Set[State[OrganizationMembershipPoke]] = Set(OrganizationMembershipPokeStates.INACTIVE))(implicit session: RSession): Option[OrganizationMembershipPoke] = {
    (for (row <- rows if row.organizationId === orgId && row.userId === userId && !row.state.inSet(excludeStates)) yield row).firstOption
  }
  def getByOrgIdAndUserIds(orgId: Id[Organization], userIds: Set[Id[User]], excludeStates: Set[State[OrganizationMembershipPoke]] = Set(OrganizationMembershipPokeStates.INACTIVE))(implicit session: RSession): Set[OrganizationMembershipPoke] = {
    (for (row <- rows if row.organizationId === orgId && row.userId.inSet(userIds) && !row.state.inSet(excludeStates)) yield row).list.toSet
  }
  def countByOrgId(orgId: Id[Organization], excludeStates: Set[State[OrganizationMembershipPoke]] = Set(OrganizationMembershipPokeStates.INACTIVE))(implicit session: RSession): Int = {
    (for (row <- rows if row.organizationId === orgId && !row.state.inSet(excludeStates)) yield row).length.run
  }
  def deactivate(model: OrganizationMembershipPoke)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}
