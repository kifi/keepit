package com.keepit.slack

import com.google.inject.Inject
import com.keepit.model.Keep
import com.keepit.slack.models._
import org.joda.time.DateTime

import scala.concurrent.Future

trait SlackIngestionCommander {
  def ingestFromSlackChannel(channelId: SlackChannelId): Future[Unit]
  def ingestAll(): Future[Unit]
}

class SlackIngestionCommanderImpl @Inject() (
    slackClient: SlackClient) {

  def ingestFromSlackChannelToLibrary(slackUserId: SlackUserId, slackTeamId: SlackTeamId, slackChannelName: SlackChannelName): Future[Seq[Keep]] = {
    ???
  }

  def ingest(integration: SlackChannelToLibrary): Future[Seq[Keep]] = {
    ???
  }

  private def getLatestMessagesWithLinks(token: SlackAccessToken, channelName: SlackChannelName, lastMessageAt: Option[DateTime], lastMessageTimestamp: Option[SlackMessageTimestamp]): Future[Seq[_]] = {
    ???
  }
}
