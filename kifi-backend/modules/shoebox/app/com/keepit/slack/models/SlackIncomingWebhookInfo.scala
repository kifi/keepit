package com.keepit.slack.models

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db.{ ModelWithState, Id, State, States }
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.json.{ JsNull, Json, JsValue }

abstract class SlackIntegrationStatus(status: String)
object SlackIntegrationStatus {
  case object On extends SlackIntegrationStatus("on")
  case object Off extends SlackIntegrationStatus("off")
}

case class SlackIncomingWebhookInfo(
    id: Option[Id[SlackIncomingWebhookInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[SlackIncomingWebhookInfo] = SlackIncomingWebhookInfoStates.ACTIVE,
    membershipId: Id[SlackTeamMembership],
    webhook: SlackIncomingWebhook,
    lastPostedAt: DateTime, // all hooks should be tested once upon initial integration
    lastFailedAt: Option[DateTime],
    lastFailure: Option[SlackAPIFailure]) extends ModelWithState[SlackIncomingWebhookInfo] {
  def withId(id: Id[SlackIncomingWebhookInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object SlackIncomingWebhookInfoStates extends States[SlackIncomingWebhookInfo]

@ImplementedBy(classOf[SlackIncomingWebhookInfoRepoImpl])
trait SlackIncomingWebhookInfoRepo extends Repo[SlackIncomingWebhookInfo]

@Singleton
class SlackIncomingWebhookInfoRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[SlackIncomingWebhookInfo] with SlackIncomingWebhookInfoRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val channelColumnType = SlackDbColumnTypes.channel(db)

  private def infoFromDbRow(
    id: Option[Id[SlackIncomingWebhookInfo]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[SlackIncomingWebhookInfo],
    membershipId: Id[SlackTeamMembership],
    channel: SlackChannel,
    url: String,
    configUrl: String,
    lastPostedAt: DateTime,
    lastFailedAt: Option[DateTime],
    lastFailure: Option[JsValue]) = {
    SlackIncomingWebhookInfo(
      id,
      createdAt,
      updatedAt,
      state,
      membershipId,
      SlackIncomingWebhook(channel = channel, url = url, configUrl = configUrl),
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
    info.membershipId,
    info.webhook.channel,
    info.webhook.url,
    info.webhook.configUrl,
    info.lastPostedAt,
    info.lastFailedAt,
    info.lastFailure.map(Json.toJson(_)).filterNot(_ == JsNull)
  ))

  type RepoImpl = SlackIncomingWebhookInfoTable

  class SlackIncomingWebhookInfoTable(tag: Tag) extends RepoTable[SlackIncomingWebhookInfo](db, tag, "slack_incoming_webhook_info") {
    def membershipId = column[Id[SlackTeamMembership]]("membership_id", O.NotNull)
    def channel = column[SlackChannel]("channel", O.NotNull)
    def url = column[String]("url", O.NotNull)
    def configUrl = column[String]("config_url", O.NotNull)
    def lastPostedAt = column[DateTime]("last_posted_at", O.NotNull)
    def lastFailedAt = column[Option[DateTime]]("last_failed_at", O.Nullable)
    def lastFailure = column[Option[JsValue]]("last_failure", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, membershipId, channel, url, configUrl, lastPostedAt, lastFailedAt, lastFailure) <> ((infoFromDbRow _).tupled, infoToDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === SlackIncomingWebhookInfoStates.ACTIVE)
  def table(tag: Tag) = new SlackIncomingWebhookInfoTable(tag)
  initTable()
  override def deleteCache(info: SlackIncomingWebhookInfo)(implicit session: RSession): Unit = {}
  override def invalidateCache(info: SlackIncomingWebhookInfo)(implicit session: RSession): Unit = {}
}
