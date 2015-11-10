package com.keepit.slack.models

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db.{ ModelWithState, Id, State, States }
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.DateTime
import play.api.libs.json.{ Json, JsValue }

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
}

object SlackTeamMembershipStates extends States[SlackTeamMembership]

@ImplementedBy(classOf[SlackTeamMembershipRepoImpl])
trait SlackTeamMembershipRepo extends Repo[SlackTeamMembership]

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

  def membershipFromDbRow(
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
    scope: JsValue) = SlackTeamMembership(id, createdAt, updatedAt, state, userId, slackUserId, slackUsername, slackTeamId, slackTeamName, token, scope.as[Set[SlackAuthScope]])

  def membershipToDbRow(membership: SlackTeamMembership) = Some((
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
}

