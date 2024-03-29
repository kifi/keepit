package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
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
  def getByUserId(userId: Id[User], limit: Limit, offset: Offset, excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership]
  def getAllByUserId(userId: Id[User], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership]
  def getSortedMembershipsByOrgId(orgId: Id[Organization], offset: Offset, limit: Limit, excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership]
  def getAllByOrgId(orgId: Id[Organization], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Set[OrganizationMembership]
  def getAllByIds(membershipIds: Set[Id[OrganizationMembership]], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[OrganizationMembership], OrganizationMembership]
  def getAllByUserIds(userIds: Set[Id[User]], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[User], Set[OrganizationMembership]]
  def getByOrgIdAndUserId(orgId: Id[Organization], userId: Id[User], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Option[OrganizationMembership]
  def getByOrgIdAndUserIds(orgId: Id[Organization], userIds: Set[Id[User]], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership]
  def getByOrgIdsAndUserId(orgIds: Set[Id[Organization]], userId: Id[User], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[Organization], Option[OrganizationMembership]]
  def countByOrgId(orgId: Id[Organization], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Int
  def deactivate(model: OrganizationMembership)(implicit session: RWSession): OrganizationMembership
  def getTeammates(userId: Id[User])(implicit session: RSession): Set[Id[User]]
  def getByRole(orgId: Id[Organization], role: OrganizationRole, excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[Id[User]]
}

@Singleton
class OrganizationMembershipRepoImpl @Inject() (
    primaryOrgForUserCache: PrimaryOrgForUserCache,
    orgMembersCache: OrganizationMembersCache,
    orgPermissionsNamespaceCache: OrganizationPermissionsNamespaceCache,
    orgPermissionsCache: OrganizationPermissionsCache,
    relevantSuggestedLibrariesCache: RelevantSuggestedLibrariesCache,
    val db: DataBaseComponent,
    val clock: Clock) extends OrganizationMembershipRepo with DbRepo[OrganizationMembership] with SeqNumberDbFunction[OrganizationMembership] with Logging {

  override def deleteCache(model: OrganizationMembership)(implicit session: RSession): Unit = {
    relevantSuggestedLibrariesCache.remove(RelevantSuggestedLibrariesKey(model.userId))
    primaryOrgForUserCache.remove(PrimaryOrgForUserKey(model.userId))
    orgMembersCache.remove(OrganizationMembersKey(model.organizationId))

    orgPermissionsNamespaceCache.get(OrganizationPermissionsNamespaceKey(model.organizationId)).foreach { namespace =>
      orgPermissionsCache.remove(OrganizationPermissionsKey(model.organizationId, namespace, Some(model.userId)))
    }
  }

  override def invalidateCache(model: OrganizationMembership)(implicit session: RSession): Unit = {
    relevantSuggestedLibrariesCache.remove(RelevantSuggestedLibrariesKey(model.userId))
    primaryOrgForUserCache.remove(PrimaryOrgForUserKey(model.userId))
    orgMembersCache.remove(OrganizationMembersKey(model.organizationId))

    orgPermissionsNamespaceCache.get(OrganizationPermissionsNamespaceKey(model.organizationId)).foreach { namespace =>
      orgPermissionsCache.remove(OrganizationPermissionsKey(model.organizationId, namespace, Some(model.userId)))
    }
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
    role: OrganizationRole) = {
    OrganizationMembership(id, createdAt, updatedAt, state, seq, organizationId, userId, role)
  }

  def unapplyToDbRow(member: OrganizationMembership) = {
    Some((member.id,
      member.createdAt,
      member.updatedAt,
      member.state,
      member.seq,
      member.organizationId,
      member.userId,
      member.role))
  }

  implicit val organizationRoleMapper = MappedColumnType.base[OrganizationRole, String](_.value, OrganizationRole(_))

  type RepoImpl = OrganizationMembershipTable
  class OrganizationMembershipTable(tag: Tag) extends RepoTable[OrganizationMembership](db, tag, "organization_membership") with SeqNumberColumn[OrganizationMembership] with NamedColumns {

    def organizationId = column[Id[Organization]]("organization_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def role = column[OrganizationRole]("role", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, seq, organizationId, userId, role) <> ((applyFromDbRow _).tupled, unapplyToDbRow _)
  }

  def table(tag: Tag) = new OrganizationMembershipTable(tag)
  initTable()

  def activeRows = rows.filter(_.state === OrganizationMembershipStates.ACTIVE)

  override def save(model: OrganizationMembership)(implicit session: RWSession): OrganizationMembership = {
    super.save(model.copy(seq = deferredSeqNum()))
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

  def getAllByUserIds(userIds: Set[Id[User]], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[User], Set[OrganizationMembership]] = {
    rows.filter(row => row.userId.inSet(userIds) && row.state =!= excludeState.orNull).list.groupBy(_.userId).mapValues(_.toSet)
  }

  override def getSortedMembershipsByOrgId(orgId: Id[Organization], offset: Offset, limit: Limit, excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"""
      select om.id
      from organization org inner join organization_membership om on org.id = om.organization_id inner join user u on om.user_id = u.id
      where om.state != ${excludeState.map(_.value).orNull} and org.id = $orgId
      order by case when om.user_id = org.owner_id then 0 else 1 end asc,
      case when om.user_id != org.owner_id then CONCAT(u.first_name, u.last_name) end asc
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

  def getAllByUserId(userId: Id[User], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership] = {
    (for { row <- rows if row.userId === userId && row.state =!= excludeState.orNull } yield row).list
  }
  def getByUserId(userId: Id[User], limit: Limit, offset: Offset, excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership] = {
    (for { row <- rows if row.userId === userId && row.state =!= excludeState.orNull } yield row).drop(offset.value).take(limit.value).list
  }

  def getByOrgIdAndUserIds(orgId: Id[Organization], userIds: Set[Id[User]], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[OrganizationMembership] = {
    excludeState match {
      case None => (for { row <- rows if row.organizationId === orgId && row.userId.inSet(userIds) } yield row).list
      case Some(exclude) => (for { row <- rows if row.organizationId === orgId && row.userId.inSet(userIds) && row.state =!= excludeState } yield row).list
    }
  }
  def getByOrgIdsAndUserId(orgIds: Set[Id[Organization]], userId: Id[User], excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Map[Id[Organization], Option[OrganizationMembership]] = {
    val found = rows.filter(r => r.userId === userId && r.organizationId.inSet(orgIds) && r.state =!= excludeState.orNull).map(r => (r.organizationId, r)).list.toMap
    orgIds.map { orgId => orgId -> found.get(orgId) }.toMap
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

  def getByRole(orgId: Id[Organization], role: OrganizationRole, excludeState: Option[State[OrganizationMembership]] = Some(OrganizationMembershipStates.INACTIVE))(implicit session: RSession): Seq[Id[User]] = {
    excludeState match {
      case Some(exclude) => (for { row <- rows if row.organizationId === orgId && row.state =!= exclude && row.role === role } yield row.userId).list
      case None => (for { row <- rows if row.organizationId === orgId && row.role === role } yield row.userId).list
    }

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
