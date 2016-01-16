package com.keepit.slack.models

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db.{ ModelWithState, Id, State, States }
import com.keepit.common.oauth.SlackIdentity
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.social.{ UserIdentity, UserIdentityIdentityIdKey, UserIdentityCache }
import org.joda.time.DateTime
import play.api.libs.json.{ Json, JsValue }

import scala.util.{ Success, Failure, Try }

case class SlackTeamMembershipInternRequest(
  userId: Option[Id[User]],
  slackUserId: SlackUserId,
  slackUsername: SlackUsername,
  slackTeamId: SlackTeamId,
  slackTeamName: SlackTeamName,
  token: SlackAccessToken,
  scopes: Set[SlackAuthScope],
  slackUser: Option[SlackUserInfo])

case class InvalidSlackAccountOwnerException(requestingUserId: Id[User], membership: SlackTeamMembership)
  extends Exception(s"Slack account ${membership.slackUsername.value} in team ${membership.slackTeamName.value} already belongs to Kifi user ${membership.userId}")

case class SlackTokenWithScopes(token: SlackAccessToken, scopes: Set[SlackAuthScope])
object SlackTokenWithScopes {
  def unapply(stm: SlackTeamMembership): Option[(SlackAccessToken, Set[SlackAuthScope])] = stm.token.map(_ -> stm.scopes)
}

object SlackTeamMembership {
  def toIdentity(membership: SlackTeamMembership): UserIdentity = {
    UserIdentity(
      membership.userId,
      SlackIdentity(
        membership.slackTeamId,
        membership.slackTeamName,
        membership.slackUserId,
        membership.slackUsername,
        membership.token,
        membership.scopes,
        membership.slackUser
      )
    )
  }
}

case class SlackTeamMembership(
    id: Option[Id[SlackTeamMembership]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[SlackTeamMembership] = SlackTeamMembershipStates.ACTIVE,
    userId: Option[Id[User]],
    slackUserId: SlackUserId,
    slackUsername: SlackUsername,
    slackTeamId: SlackTeamId,
    slackTeamName: SlackTeamName,
    token: Option[SlackAccessToken],
    scopes: Set[SlackAuthScope],
    slackUser: Option[SlackUserInfo]) extends ModelWithState[SlackTeamMembership] {
  def withId(id: Id[SlackTeamMembership]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == SlackTeamMembershipStates.ACTIVE
  def tokenWithScopes: Option[SlackTokenWithScopes] = token.map(SlackTokenWithScopes(_, scopes))

  def revoked = this.copy(token = None, scopes = Set.empty)
  def sanitizeForDelete = this.copy(userId = None, token = None, scopes = Set.empty, state = SlackTeamMembershipStates.INACTIVE)
}

object SlackTeamMembershipStates extends States[SlackTeamMembership]

@ImplementedBy(classOf[SlackTeamMembershipRepoImpl])
trait SlackTeamMembershipRepo extends Repo[SlackTeamMembership] {
  def getBySlackTeam(slackTeamId: SlackTeamId)(implicit session: RSession): Set[SlackTeamMembership]
  def getBySlackTeamAndUser(slackTeamId: SlackTeamId, slackUserId: SlackUserId, excludeState: Option[State[SlackTeamMembership]] = Some(SlackTeamMembershipStates.INACTIVE))(implicit session: RSession): Option[SlackTeamMembership]

  def internMembership(request: SlackTeamMembershipInternRequest)(implicit session: RWSession): SlackTeamMembership
  def getBySlackUserIds(ids: Set[SlackUserId])(implicit session: RSession): Map[SlackUserId, SlackTeamMembership]
  def getByToken(token: SlackAccessToken)(implicit session: RSession): Option[SlackTeamMembership]
  def getByUserId(userId: Id[User])(implicit session: RSession): Seq[SlackTeamMembership]

  def deactivate(model: SlackTeamMembership)(implicit session: RWSession): Unit
}

@Singleton
class SlackTeamMembershipRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    userIdentityCache: UserIdentityCache) extends DbRepo[SlackTeamMembership] with SlackTeamMembershipRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackUserIdColumnType = SlackDbColumnTypes.userId(db)
  implicit val slackUsernameColumnType = SlackDbColumnTypes.username(db)
  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackTeamNameColumnType = SlackDbColumnTypes.teamName(db)
  implicit val tokenColumnType = MappedColumnType.base[SlackAccessToken, String](_.token, SlackAccessToken(_))
  implicit val scopesFormat = SlackAuthScope.dbFormat

  private def membershipFromDbRow(
    id: Option[Id[SlackTeamMembership]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[SlackTeamMembership],
    userId: Option[Id[User]],
    slackUserId: SlackUserId,
    slackUsername: SlackUsername,
    slackTeamId: SlackTeamId,
    slackTeamName: SlackTeamName,
    token: Option[SlackAccessToken],
    scopes: JsValue,
    slackUser: Option[JsValue]) = {
    SlackTeamMembership(
      id,
      createdAt,
      updatedAt,
      state,
      userId,
      slackUserId,
      slackUsername,
      slackTeamId,
      slackTeamName,
      token,
      scopes.as[Set[SlackAuthScope]],
      slackUser.map(_.as[SlackUserInfo])
    )
  }

  private def membershipToDbRow(membership: SlackTeamMembership) = Some((
    membership.id,
    membership.createdAt,
    membership.updatedAt,
    membership.state,
    membership.userId,
    membership.slackUserId,
    membership.slackUsername,
    membership.slackTeamId,
    membership.slackTeamName,
    membership.token,
    Json.toJson(membership.scopes),
    membership.slackUser.map(Json.toJson(_))
  ))

  type RepoImpl = SlackTeamMembershipTable

  class SlackTeamMembershipTable(tag: Tag) extends RepoTable[SlackTeamMembership](db, tag, "slack_team_membership") {
    def userId = column[Option[Id[User]]]("user_id", O.Nullable)
    def slackUserId = column[SlackUserId]("slack_user_id", O.NotNull)
    def slackUsername = column[SlackUsername]("slack_username", O.NotNull)
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackTeamName = column[SlackTeamName]("slack_team_name", O.NotNull)
    def token = column[Option[SlackAccessToken]]("token", O.Nullable)
    def scopes = column[JsValue]("scopes", O.NotNull)
    def slackUser = column[Option[JsValue]]("slack_user", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, userId, slackUserId, slackUsername, slackTeamId, slackTeamName, token, scopes, slackUser) <> ((membershipFromDbRow _).tupled, membershipToDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === SlackTeamMembershipStates.ACTIVE)
  def table(tag: Tag) = new SlackTeamMembershipTable(tag)
  initTable()
  override def deleteCache(membership: SlackTeamMembership)(implicit session: RSession): Unit = {
    userIdentityCache.remove(UserIdentityIdentityIdKey(membership.slackTeamId, membership.slackUserId))
  }

  override def invalidateCache(membership: SlackTeamMembership)(implicit session: RSession): Unit = deleteCache(membership)

  def getBySlackTeam(slackTeamId: SlackTeamId)(implicit session: RSession): Set[SlackTeamMembership] = {
    activeRows.filter(row => row.slackTeamId === slackTeamId).list.toSet
  }
  def getBySlackTeamAndUser(slackTeamId: SlackTeamId, slackUserId: SlackUserId, excludeState: Option[State[SlackTeamMembership]] = Some(SlackTeamMembershipStates.INACTIVE))(implicit session: RSession): Option[SlackTeamMembership] = {
    rows.filter(row => row.slackTeamId === slackTeamId && row.slackUserId === slackUserId && row.state =!= excludeState.orNull).firstOption
  }
  def internMembership(request: SlackTeamMembershipInternRequest)(implicit session: RWSession): SlackTeamMembership = {
    getBySlackTeamAndUser(request.slackTeamId, request.slackUserId, excludeState = None) match {
      case Some(membership) if membership.isActive =>
        val updated = membership.copy(
          userId = request.userId orElse membership.userId, // let a Kifi user steal a slack membership
          slackUsername = request.slackUsername,
          slackTeamName = request.slackTeamName,
          token = Some(request.token),
          scopes = request.scopes,
          slackUser = request.slackUser orElse membership.slackUser
        )
        if (updated == membership) membership else save(updated)
      case inactiveMembershipOpt =>
        val newMembership = SlackTeamMembership(
          id = inactiveMembershipOpt.map(_.id.get),
          userId = request.userId,
          slackUserId = request.slackUserId,
          slackUsername = request.slackUsername,
          slackTeamId = request.slackTeamId,
          slackTeamName = request.slackTeamName,
          token = Some(request.token),
          scopes = request.scopes,
          slackUser = request.slackUser
        )
        save(newMembership)
    }
  }

  def getBySlackUserIds(ids: Set[SlackUserId])(implicit session: RSession): Map[SlackUserId, SlackTeamMembership] = {
    activeRows.filter(_.slackUserId.inSet(ids)).map(r => (r.slackUserId, r)).list.toMap
  }
  def getByToken(token: SlackAccessToken)(implicit session: RSession): Option[SlackTeamMembership] = {
    activeRows.filter(_.token === token).firstOption
  }

  def getByUserId(userId: Id[User])(implicit session: RSession): Seq[SlackTeamMembership] = {
    activeRows.filter(_.userId === userId).list
  }
  def deactivate(model: SlackTeamMembership)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}

