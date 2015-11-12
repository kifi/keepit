package com.keepit.slack.models

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db.{ ModelWithState, Id, State, States }
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.DateTime
import play.api.libs.json.{ JsNull, Json, JsValue }

// track revokedSince
case class SlackIncomingWebhookInfo(
    id: Option[Id[SlackIncomingWebhookInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[SlackIncomingWebhookInfo] = SlackIncomingWebhookInfoStates.ACTIVE,
    ownerId: Id[User],
    slackUserId: SlackUserId,
    slackTeamId: SlackTeamId,
    slackChannelId: Option[SlackChannelId],
    webhook: SlackIncomingWebhook,
    lastPostedAt: Option[DateTime],
    lastFailedAt: Option[DateTime] = None,
    lastFailure: Option[SlackAPIFailure] = None) extends ModelWithState[SlackIncomingWebhookInfo] {
  def withId(id: Id[SlackIncomingWebhookInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object SlackIncomingWebhookInfoStates extends States[SlackIncomingWebhookInfo]

@ImplementedBy(classOf[SlackIncomingWebhookInfoRepoImpl])
trait SlackIncomingWebhookInfoRepo extends Repo[SlackIncomingWebhookInfo] {
  def getByIntegration(int: SlackIntegration)(implicit session: RSession): Option[SlackIncomingWebhookInfo]
  def getByOwnerAndTeamAndChannelName(ownerId: Id[User], teamId: SlackTeamId, channelName: SlackChannelName)(implicit session: RSession): Option[SlackIncomingWebhookInfo]
}

@Singleton
class SlackIncomingWebhookInfoRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[SlackIncomingWebhookInfo] with SlackIncomingWebhookInfoRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackUserIdColumnType = SlackDbColumnTypes.userId(db)
  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackChannelColumnIdType = SlackDbColumnTypes.channelId(db)
  implicit val slackChannelColumnType = SlackDbColumnTypes.channel(db)

  private def infoFromDbRow(
    id: Option[Id[SlackIncomingWebhookInfo]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[SlackIncomingWebhookInfo],
    ownerId: Id[User],
    slackUserId: SlackUserId,
    slackTeamId: SlackTeamId,
    slackChannelId: Option[SlackChannelId],
    slackChannelName: SlackChannelName,
    url: String,
    configUrl: String,
    lastPostedAt: Option[DateTime],
    lastFailedAt: Option[DateTime],
    lastFailure: Option[JsValue]) = {
    SlackIncomingWebhookInfo(
      id,
      createdAt,
      updatedAt,
      state,
      ownerId,
      slackUserId,
      slackTeamId,
      slackChannelId,
      SlackIncomingWebhook(channelName = slackChannelName, url = url, configUrl = configUrl),
      lastPostedAt,
      lastFailedAt,
      lastFailure.map(_.as[SlackAPIFailure])
    )
  }

  private def infoToDbRow(info: SlackIncomingWebhookInfo) = Some((
    info.id,
    info.createdAt,
    info.updatedAt,
    info.state,
    info.ownerId,
    info.slackUserId,
    info.slackTeamId,
    info.slackChannelId,
    info.webhook.channelName,
    info.webhook.url,
    info.webhook.configUrl,
    info.lastPostedAt,
    info.lastFailedAt,
    info.lastFailure.map(Json.toJson(_)).filterNot(_ == JsNull)
  ))

  type RepoImpl = SlackIncomingWebhookInfoTable

  class SlackIncomingWebhookInfoTable(tag: Tag) extends RepoTable[SlackIncomingWebhookInfo](db, tag, "slack_incoming_webhook_info") {
    def ownerId = column[Id[User]]("owner_id", O.NotNull)
    def slackUserId = column[SlackUserId]("slack_user_id", O.NotNull)
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackChannelId = column[Option[SlackChannelId]]("slack_channel_id", O.Nullable)
    def slackChannelName = column[SlackChannelName]("slack_channel_name", O.NotNull)
    def url = column[String]("url", O.NotNull)
    def configUrl = column[String]("config_url", O.NotNull)
    def lastPostedAt = column[Option[DateTime]]("last_posted_at", O.Nullable)
    def lastFailedAt = column[Option[DateTime]]("last_failed_at", O.Nullable)
    def lastFailure = column[Option[JsValue]]("last_failure", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, ownerId, slackUserId, slackTeamId, slackChannelId, slackChannelName, url, configUrl, lastPostedAt, lastFailedAt, lastFailure) <> ((infoFromDbRow _).tupled, infoToDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === SlackIncomingWebhookInfoStates.ACTIVE)
  def table(tag: Tag) = new SlackIncomingWebhookInfoTable(tag)
  initTable()
  override def deleteCache(info: SlackIncomingWebhookInfo)(implicit session: RSession): Unit = {}
  override def invalidateCache(info: SlackIncomingWebhookInfo)(implicit session: RSession): Unit = {}

  def add(ownerId: Id[User], slackUserId: SlackUserId, slackTeamId: SlackTeamId, slackChannelId: Option[SlackChannelId], hook: SlackIncomingWebhook, lastPostedAt: Option[DateTime] = None)(implicit session: RWSession): SlackIncomingWebhookInfo = {
    save(SlackIncomingWebhookInfo(
      ownerId = ownerId,
      slackUserId = slackUserId,
      slackTeamId = slackTeamId,
      slackChannelId = slackChannelId,
      webhook = hook,
      lastPostedAt = lastPostedAt
    ))
  }

  def getByOwnerAndTeamAndChannelName(ownerId: Id[User], teamId: SlackTeamId, channelName: SlackChannelName)(implicit session: RSession): Option[SlackIncomingWebhookInfo] = {
    rows.filter(wh => wh.ownerId === ownerId && wh.slackTeamId === teamId && wh.slackChannelName === channelName).firstOption
  }
  def getByIntegration(int: SlackIntegration)(implicit session: RSession): Option[SlackIncomingWebhookInfo] = {
    getByOwnerAndTeamAndChannelName(int.ownerId, int.slackTeamId, int.slackChannelName)
  }
}
