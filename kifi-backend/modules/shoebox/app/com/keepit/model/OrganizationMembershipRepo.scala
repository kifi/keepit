package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.{ DbSequenceAssigner, SequenceNumber, State, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import com.keepit.common.time.Clock
import org.joda.time.DateTime
import play.api.libs.json._

import scala.slick.jdbc.{ PositionedResult, GetResult }

@ImplementedBy(classOf[OrganizationMembershipRepoImpl])
trait OrganizationMembershipRepo extends Repo[OrganizationMembership] with SeqNumberFunction[OrganizationMembership] {
  def getByUserId(userId: Id[User], limit: Limit, offset: Offset, excludeStates: Set[State[OrganizationMembership]] = Set(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership]
  def getAllByUserId(userId: Id[User], excludeStates: Set[State[OrganizationMembership]] = Set(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership]
  def getSortedMembershipsByOrgId(orgId: Id[Organization], offset: Offset, limit: Limit, excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership]
  def getAllByOrgId(orgId: Id[Organization], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Set[OrganizationMembership]
  def getAllByIds(membershipIds: Set[Id[OrganizationMembership]], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[OrganizationMembership], OrganizationMembership]
  def getByOrgIdAndUserId(orgId: Id[Organization], userId: Id[User], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Option[OrganizationMembership]
  def getByOrgIdAndUserIds(orgId: Id[Organization], userIds: Set[Id[User]], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership]
  def countByOrgId(orgId: Id[Organization], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Int
  def deactivate(model: OrganizationMembership)(implicit session: RWSession): OrganizationMembership
  def getTeammates(userId: Id[User])(implicit session: RSession): Set[Id[User]]
  def primaryOrgForUser(userId: Id[User])(implicit session: RSession): Option[Id[Organization]]
}

@Singleton
class OrganizationMembershipRepoImpl @Inject() (
    primaryOrgForUserCache: PrimaryOrgForUserCache,
    val db: DataBaseComponent,
    val clock: Clock) extends OrganizationMembershipRepo with DbRepo[OrganizationMembership] with SeqNumberDbFunction[OrganizationMembership] with Logging {

  override def deleteCache(orgMember: OrganizationMembership)(implicit session: RSession): Unit = {
    primaryOrgForUserCache.remove(PrimaryOrgForUserKey(orgMember.userId))
  }

  override def invalidateCache(orgMember: OrganizationMembership)(implicit session: RSession): Unit = {
    primaryOrgForUserCache.remove(PrimaryOrgForUserKey(orgMember.userId))
  }

  import db.Driver.simple._

  def applyFromDbRow(
    id: Option[Id[OrganizationMembership]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[OrganizationMembership],
    seq: SequenceNumber[OrganizationMembership],
    organizationId: Id[Organization],
    userId: Id[User],
    role: OrganizationRole,
    permissions: Set[OrganizationPermission]) = {
    OrganizationMembership(id, createdAt, updatedAt, state, seq, organizationId, userId, role, permissions)
  }

  def unapplyToDbRow(member: OrganizationMembership) = {
    Some((member.id,
      member.createdAt,
      member.updatedAt,
      member.state,
      member.seq,
      member.organizationId,
      member.userId,
      member.role,
      member.permissions))
  }

  type RepoImpl = OrganizationMembershipTable
  class OrganizationMembershipTable(tag: Tag) extends RepoTable[OrganizationMembership](db, tag, "organization_membership") with SeqNumberColumn[OrganizationMembership] with NamedColumns {
    implicit val organizationRoleMapper = MappedColumnType.base[OrganizationRole, String](_.value, OrganizationRole(_))
    implicit val organizationPermissionsMapper = MappedColumnType.base[Set[OrganizationPermission], String](
      { permissions => Json.stringify(Json.toJson(permissions)) },
      { str => Json.parse(str).as[Set[OrganizationPermission]] }
    )

    def organizationId = column[Id[Organization]]("organization_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def role = column[OrganizationRole]("role", O.NotNull)
    def permissions = column[Set[OrganizationPermission]]("permissions", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, seq, organizationId, userId, role, permissions) <> ((applyFromDbRow _).tupled, unapplyToDbRow _)
  }

  def table(tag: Tag) = new OrganizationMembershipTable(tag)
  initTable()

  override def save(model: OrganizationMembership)(implicit session: RWSession): OrganizationMembership = {
    super.save(model.copy(seq = deferredSeqNum()))
  }

  def primaryOrgForUser(userId: Id[User])(implicit session: RSession): Option[Id[Organization]] = {
    (for (row <- rows if row.userId === userId) yield row.organizationId).firstOption
  }

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

  def getAllByIds(membershipIds: Set[Id[OrganizationMembership]], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[OrganizationMembership], OrganizationMembership] = {
    (for (row <- rows if row.id.inSet(membershipIds) && row.state =!= excludeState.orNull) yield (row.id, row)).list.toMap
  }

  override def getSortedMembershipsByOrgId(orgId: Id[Organization], offset: Offset, limit: Limit, excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"""
      select om.id
      from organization org inner join organization_membership om on org.id = om.organization_id inner join user u on om.user_id = u.id
      where om.state != ${excludeState.map(_.value).orNull} and org.id = $orgId
      order by case when om.user_id = org.owner_id then 0 else 1 end asc,
      case when om.user_id != org.owner_id then u.last_name end asc
      limit ${offset.value}, ${limit.value};
      """
    val ids = query.as[Id[OrganizationMembership]].list.toSeq

    val mems = getAllByIds(ids.toSet)

    ids.map(id => mems(id))
  }

  private val getAllByOrgIdWithExcludeCompiled = Compiled { (orgId: Column[Id[Organization]], excludeState: Column[State[OrganizationMembership]]) =>
    (for (row <- rows if row.organizationId === orgId && row.state =!= excludeState) yield row)
  }

  private val getAllByOrgIdCompiled = Compiled { (orgId: Column[Id[Organization]]) =>
    for { row <- rows if row.organizationId === orgId } yield row
  }

  def getAllByOrgId(orgId: Id[Organization], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Set[OrganizationMembership] = {
    excludeState.map(state => getAllByOrgIdWithExcludeCompiled(orgId, state)).getOrElse(getAllByOrgIdCompiled(orgId)).list.toSet
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

  def getAllByUserId(userId: Id[User], excludeStates: Set[State[OrganizationMembership]] = Set(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership] = {
    (for { row <- rows if row.userId === userId && !row.state.inSet(excludeStates) } yield row).list
  }
  def getByUserId(userId: Id[User], limit: Limit, offset: Offset, excludeStates: Set[State[OrganizationMembership]] = Set(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership] = {
    (for { row <- rows if row.userId === userId && !row.state.inSet(excludeStates) } yield row).drop(offset.value).take(limit.value).list
  }

  def getByOrgIdAndUserIds(orgId: Id[Organization], userIds: Set[Id[User]], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership] = {
    excludeState match {
      case None => (for { row <- rows if row.organizationId === orgId && row.userId.inSet(userIds) } yield row).list
      case Some(exclude) => (for { row <- rows if row.organizationId === orgId && row.userId.inSet(userIds) && row.state =!= excludeState } yield row).list
    }
  }

  def deactivate(membership: OrganizationMembership)(implicit session: RWSession): OrganizationMembership = {
    save(membership.sanitizeForDelete)
  }

  private val getTeammatesCompiled = Compiled { (userId: Column[Id[User]]) =>
    for {
      userMembership <- rows if userMembership.userId === userId && userMembership.state === OrganizationMembershipStates.ACTIVE
      orgMembership <- rows if orgMembership.organizationId === userMembership.organizationId && orgMembership.userId =!= userId && orgMembership.state === OrganizationMembershipStates.ACTIVE
    } yield orgMembership.userId
  }

  def getTeammates(userId: Id[User])(implicit session: RSession): Set[Id[User]] = {
    getTeammatesCompiled(userId).run.toSet
  }
}

trait OrganizationMembershipSequencingPlugin extends SequencingPlugin

class OrganizationMembershipSequencingPluginImpl @Inject() (
  override val actor: ActorInstance[OrganizationMembershipSequencingActor],
  override val scheduling: SchedulingProperties) extends OrganizationMembershipSequencingPlugin

@Singleton
class OrganizationMembershipSequenceNumberAssigner @Inject() (db: Database, repo: OrganizationMembershipRepo, airbrake: AirbrakeNotifier) extends DbSequenceAssigner[OrganizationMembership](db, repo, airbrake)
class OrganizationMembershipSequencingActor @Inject() (
  assigner: OrganizationMembershipSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
