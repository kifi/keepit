package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.{ SequenceNumber, State, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import org.joda.time.DateTime

@ImplementedBy(classOf[OrganizationMembershipRepoImpl])
trait OrganizationMembershipRepo extends Repo[OrganizationMembership] with SeqNumberFunction[OrganizationMembership] {
  def getByUserId(userId: Id[User], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership]
  def getbyOrgId(orgId: Id[Organization], limit: Limit, offset: Offset, excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership]
  def getByOrgIdAndUserId(orgId: Id[Organization], userId: Id[User], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Option[OrganizationMembership]
  def getByOrgIdAndUserIds(orgId: Id[Organization], userIds: Set[Id[User]], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership]
  def countByOrgId(orgId: Id[Organization], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Int
  def deactivate(membership: Id[OrganizationMembership])(implicit session: RWSession): OrganizationMembership
}

case class Offset(value: Long) extends AnyVal
case class Limit(value: Long) extends AnyVal

@Singleton
class OrganizationMembershipRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends OrganizationMembershipRepo with DbRepo[OrganizationMembership] with SeqNumberDbFunction[OrganizationMembership] with Logging {
  override def deleteCache(orgMember: OrganizationMembership)(implicit session: RSession) {}
  override def invalidateCache(orgMember: OrganizationMembership)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = OrganizationMembershipTable
  class OrganizationMembershipTable(tag: Tag) extends RepoTable[OrganizationMembership](db, tag, "organization_membership") with SeqNumberColumn[OrganizationMembership] {
    implicit val organizationAccessMapper = MappedColumnType.base[OrganizationAccess, String](_.value, OrganizationAccess(_))

    def organizationId = column[Id[Organization]]("organization_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def access = column[OrganizationAccess]("access", O.NotNull)

    def applyFromDbRow(
      id: Option[Id[OrganizationMembership]],
      createdAt: DateTime,
      updatedAt: DateTime,
      state: State[OrganizationMembership],
      seq: SequenceNumber[OrganizationMembership],
      organizationId: Id[Organization],
      userId: Id[User],
      access: OrganizationAccess) = {
      OrganizationMembership(id, createdAt, updatedAt, state, seq, organizationId, userId, access)
    }

    def unapplyToDbRow(member: OrganizationMembership) = {
      Some((member.id,
        member.createdAt,
        member.updatedAt,
        member.state,
        member.seq,
        member.organizationId,
        member.userId,
        member.access))
    }

    def * = (id.?, createdAt, updatedAt, state, seq, organizationId, userId, access) <> ((applyFromDbRow _).tupled, unapplyToDbRow _)
  }

  def table(tag: Tag) = new OrganizationMembershipTable(tag)
  initTable()

  private val getByOrgIdAndUserIdCompiled = Compiled { (orgId: Column[Id[Organization]], userId: Column[Id[User]]) =>
    (for (row <- rows if row.organizationId === orgId && row.userId === userId) yield row)
  }

  private val getByOrgIdAndUserIdWithExcludeCompiled = Compiled { (orgId: Column[Id[Organization]], userId: Column[Id[User]], excludeState: Column[State[OrganizationMembership]]) =>
    (for (row <- rows if row.organizationId === orgId && row.userId === userId && row.state =!= excludeState) yield row)
  }

  def getByOrgIdAndUserId(organizationId: Id[Organization], userId: Id[User], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Option[OrganizationMembership] = {
    excludeState match {
      case None => getByOrgIdAndUserIdCompiled(organizationId, userId).firstOption
      case Some(exclude) => getByOrgIdAndUserIdWithExcludeCompiled(organizationId, userId, exclude).firstOption
    }
  }

  private val getByOrgIdCompiled = Compiled { (orgId: Column[Id[Organization]], count: ConstColumn[Long], offset: ConstColumn[Long]) =>
    (for { row <- rows if row.organizationId === orgId } yield row).drop(offset).take(count)
  }

  private val getByOrgIdWithExcludeCompiled = Compiled { (orgId: Column[Id[Organization]], excludeState: Column[State[OrganizationMembership]], limit: ConstColumn[Long], offset: ConstColumn[Long]) =>
    (for { row <- rows if row.organizationId === orgId && row.state =!= excludeState } yield row).drop(offset).take(limit)
  }

  def getbyOrgId(orgId: Id[Organization], limit: Limit, offset: Offset, excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership] = {
    excludeState match {
      case None => getByOrgIdCompiled(orgId, limit.value, offset.value).list
      case Some(exclude) => getByOrgIdWithExcludeCompiled(orgId, exclude, limit.value, offset.value).list
    }
  }

  def countByOrgIdCompiled = Compiled { (orgId: Column[Id[Organization]]) =>
    (for { row <- rows if row.organizationId === orgId } yield row).length
  }

  def countByOrgIdWithExcludeCompiled = Compiled { (orgId: Column[Id[Organization]], excludeState: Column[State[OrganizationMembership]]) =>
    (for { row <- rows if row.organizationId === orgId && row.state =!= excludeState } yield row).length
  }

  def countByOrgId(orgId: Id[Organization], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Int = {
    excludeState match {
      case None => countByOrgIdCompiled(orgId).run
      case Some(exclude) => countByOrgIdWithExcludeCompiled(orgId, exclude).run
    }
  }

  def getByUserIdCompiled = Compiled { (userId: Column[Id[User]]) =>
    (for { row <- rows if row.userId === userId } yield row)
  }

  def getByUserIdWithExcludeCompiled = Compiled { (userId: Column[Id[User]], excludeState: Column[State[OrganizationMembership]]) =>
    (for { row <- rows if row.userId === userId && row.state =!= excludeState } yield row)
  }

  def getByUserId(userId: Id[User], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership] = {
    excludeState match {
      case None => getByUserIdCompiled(userId).list
      case Some(exclude) => getByUserIdWithExcludeCompiled(userId, exclude).list
    }
  }

  def getByOrgIdAndUserIds(orgId: Id[Organization], userIds: Set[Id[User]], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership] = {
    excludeState match {
      case None => (for { row <- rows if row.organizationId === orgId && row.userId.inSet(userIds) } yield row).list
      case Some(exclude) => (for { row <- rows if row.organizationId === orgId && row.userId.inSet(userIds) && row.state =!= excludeState } yield row).list
    }
  }

  def deactivate(membership: Id[OrganizationMembership])(implicit session: RWSession): OrganizationMembership = {
    save(get(membership).copy(state = OrganizationMembershipStates.INACTIVE))
  }
}
