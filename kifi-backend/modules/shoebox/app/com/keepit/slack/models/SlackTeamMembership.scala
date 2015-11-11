package com.keepit.slack.models

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db.{ ModelWithState, Id, State, States }
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.DateTime
import play.api.libs.json.{ Json, JsValue }

import scala.util.{ Success, Failure, Try }

case class SlackTeamMembershipInternRequest(
  userId: Id[User],
  slackUserId: SlackUserId,
  slackUsername: SlackUsername,
  slackTeamId: SlackTeamId,
  slackTeamName: SlackTeamName,
  token: SlackAccessToken,
  scope: Set[SlackAuthScope])

case class InvalidSlackAccountOwnerException(requestingUserId: Id[User], membership: SlackTeamMembership)
  extends Exception(s"Slack account ${membership.slackUsername.value} in team ${membership.slackTeamName.value} already belongs to Kifi user ${membership.userId}")

case class SlackTeamMembership(
    id: Option[Id[SlackTeamMembership]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[SlackTeamMembership] = SlackTeamMembershipStates.ACTIVE,
    userId: Id[User],
    slackUserId: SlackUserId,
    slackUsername: SlackUsername,
    slackTeamId: SlackTeamId,
    slackTeamName: SlackTeamName,
    token: Option[SlackAccessToken],
    scope: Set[SlackAuthScope]) extends ModelWithState[SlackTeamMembership] {
  def withId(id: Id[SlackTeamMembership]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = (state == SlackTeamMembershipStates.ACTIVE)
}

object SlackTeamMembershipStates extends States[SlackTeamMembership]

@ImplementedBy(classOf[SlackTeamMembershipRepoImpl])
trait SlackTeamMembershipRepo extends Repo[SlackTeamMembership] {
  def getBySlackTeamAndUser(slackTeamId: SlackTeamId, slackUserId: SlackUserId, excludeState: Option[State[SlackTeamMembership]] = Some(SlackTeamMembershipStates.INACTIVE))(implicit session: RSession): Option[SlackTeamMembership]
  def internBySlackTeamAndUser(request: SlackTeamMembershipInternRequest)(implicit session: RWSession): Try[SlackTeamMembership]
}

@Singleton
class SlackTeamMembershipRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[SlackTeamMembership] with SlackTeamMembershipRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackUserIdColumnType = SlackDbColumnTypes.userId(db)
  implicit val slackUsernameColumnType = SlackDbColumnTypes.username(db)
  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackTeamNameColumnType = SlackDbColumnTypes.teamName(db)
  implicit val tokenColumnType = MappedColumnType.base[SlackAccessToken, String](_.token, SlackAccessToken(_))

  private def membershipFromDbRow(
    id: Option[Id[SlackTeamMembership]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[SlackTeamMembership],
    userId: Id[User],
    slackUserId: SlackUserId,
    slackUsername: SlackUsername,
    slackTeamId: SlackTeamId,
    slackTeamName: SlackTeamName,
    token: Option[SlackAccessToken],
    scope: JsValue) = {
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
      scope.as[Set[SlackAuthScope]]
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
    Json.toJson(membership.scope)
  ))

  type RepoImpl = SlackTeamMembershipTable

  class SlackTeamMembershipTable(tag: Tag) extends RepoTable[SlackTeamMembership](db, tag, "slack_team_membership") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def slackUserId = column[SlackUserId]("slack_user_id", O.NotNull)
    def slackUsername = column[SlackUsername]("slack_username", O.NotNull)
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackTeamName = column[SlackTeamName]("slack_team_name", O.NotNull)
    def token = column[Option[SlackAccessToken]]("token", O.Nullable)
    def scope = column[JsValue]("scope", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, userId, slackUserId, slackUsername, slackTeamId, slackTeamName, token, scope) <> ((membershipFromDbRow _).tupled, membershipToDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === SlackTeamMembershipStates.ACTIVE)
  def table(tag: Tag) = new SlackTeamMembershipTable(tag)
  initTable()
  override def deleteCache(membership: SlackTeamMembership)(implicit session: RSession): Unit = {}
  override def invalidateCache(membership: SlackTeamMembership)(implicit session: RSession): Unit = {}

  def getBySlackTeamAndUser(slackTeamId: SlackTeamId, slackUserId: SlackUserId, excludeState: Option[State[SlackTeamMembership]] = Some(SlackTeamMembershipStates.INACTIVE))(implicit session: RSession): Option[SlackTeamMembership] = {
    rows.filter(row => row.slackTeamId === slackTeamId && row.slackUserId === slackUserId && row.state =!= excludeState.orNull).firstOption
  }

  def internBySlackTeamAndUser(request: SlackTeamMembershipInternRequest)(implicit session: RWSession): Try[SlackTeamMembership] = {
    getBySlackTeamAndUser(request.slackTeamId, request.slackUserId, excludeState = None) match {
      case Some(membership) if membership.isActive =>
        if (membership.userId != request.userId) Failure(InvalidSlackAccountOwnerException(request.userId, membership))
        else {
          val updated = membership.copy(
            slackUserId = request.slackUserId,
            slackUsername = request.slackUsername,
            slackTeamId = request.slackTeamId,
            slackTeamName = request.slackTeamName,
            token = Some(request.token),
            scope = request.scope
          )
          val saved = if (updated == membership) membership else save(updated)
          Success(saved)
        }
      case inactiveMembershipOpt =>
        val newMembership = SlackTeamMembership(
          id = inactiveMembershipOpt.flatMap(_.id),
          userId = request.userId,
          slackUserId = request.slackUserId,
          slackUsername = request.slackUsername,
          slackTeamId = request.slackTeamId,
          slackTeamName = request.slackTeamName,
          token = Some(request.token),
          scope = request.scope
        )
        Success(save(newMembership))
    }
  }
}

