package com.keepit.slack.models

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db.{ ModelWithState, Id, State, States }
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.core._

// track revokedSince
case class SlackIncomingWebhookInfo(
    id: Option[Id[SlackIncomingWebhookInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[SlackIncomingWebhookInfo] = SlackIncomingWebhookInfoStates.ACTIVE,
    slackUserId: SlackUserId,
    slackTeamId: SlackTeamId,
    slackChannelId: SlackChannelId,
    webhook: SlackIncomingWebhook,
    lastPostedAt: Option[DateTime],
    lastFailedAt: Option[DateTime] = None,
    lastFailure: Option[SlackAPIErrorResponse] = None) extends ModelWithState[SlackIncomingWebhookInfo] {
  def withId(id: Id[SlackIncomingWebhookInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withLastFailure(f: SlackAPIErrorResponse) = this.copy(lastFailure = Some(f))
  def withLastFailedAt(time: DateTime) = this.copy(lastFailedAt = Some(time))
  def withCleanSlate = this.copy(lastFailure = None, lastFailedAt = None)
  def withLastPostedAt(time: DateTime) = this.copy(lastPostedAt = Some(time))
}

object SlackIncomingWebhookInfoStates extends States[SlackIncomingWebhookInfo]

@ImplementedBy(classOf[SlackIncomingWebhookInfoRepoImpl])
trait SlackIncomingWebhookInfoRepo extends Repo[SlackIncomingWebhookInfo] {
  def getByChannel(teamId: SlackTeamId, channelId: SlackChannelId)(implicit session: RSession): Seq[SlackIncomingWebhookInfo]
  def getByChannelIds(slackChannelIds: Set[SlackChannelId])(implicit session: RSession): Map[SlackChannelId, Seq[SlackIncomingWebhookInfo]]
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
  implicit val slackChannelColumnType = SlackDbColumnTypes.channelName(db)

  private def infoFromDbRow(
    id: Option[Id[SlackIncomingWebhookInfo]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[SlackIncomingWebhookInfo],
    slackUserId: SlackUserId,
    slackTeamId: SlackTeamId,
    slackChannelId: SlackChannelId,
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
      slackUserId,
      slackTeamId,
      slackChannelId,
      SlackIncomingWebhook(channelName = slackChannelName, channelId = slackChannelId, url = url, configUrl = configUrl),
      lastPostedAt,
      lastFailedAt,
      lastFailure.map(_.as[SlackAPIErrorResponse])
    )
  }

  private def infoToDbRow(info: SlackIncomingWebhookInfo) = Some((
    info.id,
    info.createdAt,
    info.updatedAt,
    info.state,
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
    def slackUserId = column[SlackUserId]("slack_user_id", O.NotNull)
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackChannelId = column[SlackChannelId]("slack_channel_id", O.NotNull)
    def slackChannelName = column[SlackChannelName]("slack_channel_name", O.NotNull)
    def url = column[String]("url", O.NotNull)
    def configUrl = column[String]("config_url", O.NotNull)
    def lastPostedAt = column[Option[DateTime]]("last_posted_at", O.Nullable)
    def lastFailedAt = column[Option[DateTime]]("last_failed_at", O.Nullable)
    def lastFailure = column[Option[JsValue]]("last_failure", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, slackUserId, slackTeamId, slackChannelId, slackChannelName, url, configUrl, lastPostedAt, lastFailedAt, lastFailure) <> ((infoFromDbRow _).tupled, infoToDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === SlackIncomingWebhookInfoStates.ACTIVE)
  private def workingRows = activeRows.filter(row => row.lastFailure.isEmpty)
  def table(tag: Tag) = new SlackIncomingWebhookInfoTable(tag)
  initTable()
  override def deleteCache(info: SlackIncomingWebhookInfo)(implicit session: RSession): Unit = {}
  override def invalidateCache(info: SlackIncomingWebhookInfo)(implicit session: RSession): Unit = {}

  def add(slackUserId: SlackUserId, slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, hook: SlackIncomingWebhook, lastPostedAt: Option[DateTime] = None)(implicit session: RWSession): SlackIncomingWebhookInfo = {
    save(SlackIncomingWebhookInfo(
      slackUserId = slackUserId,
      slackTeamId = slackTeamId,
      slackChannelId = slackChannelId,
      webhook = hook,
      lastPostedAt = lastPostedAt
    ))
  }

  def getByChannel(teamId: SlackTeamId, channelId: SlackChannelId)(implicit session: RSession): Seq[SlackIncomingWebhookInfo] = {
    workingRows.filter(row => row.slackTeamId === teamId && row.slackChannelId === channelId).list
  }

  def getByChannelIds(slackChannelIds: Set[SlackChannelId])(implicit session: RSession): Map[SlackChannelId, Seq[SlackIncomingWebhookInfo]] = {
    workingRows.filter(row => row.slackChannelId.inSet(slackChannelIds)).list.groupBy(_.slackChannelId)
  }
}
