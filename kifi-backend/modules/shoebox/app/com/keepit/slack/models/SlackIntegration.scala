package com.keepit.slack.models

import com.keepit.common.db.Id
import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.common.reflection.Enumerator
import com.keepit.model.{ Library, User }

abstract class SlackIntegrationStatus(val status: String)
object SlackIntegrationStatus extends Enumerator[SlackIntegrationStatus] {
  case object On extends SlackIntegrationStatus("on")
  case object Off extends SlackIntegrationStatus("off")
  def all = _all

  def columnType(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackIntegrationStatus, String](_.status, status => all.find(_.status == status).getOrElse { throw new IllegalStateException(s"Unkwown SlackIntegrationStatus: $status") })
  }
}

trait SlackIntegration {
  def ownerId: Id[User]
  def slackUserId: SlackUserId
  def slackTeamId: SlackTeamId
  def slackChannelId: SlackChannelId
  def slackChannel: SlackChannel
  def libraryId: Id[Library]
  def status: SlackIntegrationStatus
}

case class SlackIntegrationRequest(
  userId: Id[User],
  slackUserId: SlackUserId,
  slackTeamId: SlackTeamId,
  slackChannelId: SlackChannelId,
  slackChannel: SlackChannel,
  libraryId: Id[Library])

