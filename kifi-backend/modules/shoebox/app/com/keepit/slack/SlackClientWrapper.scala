package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.slack.models._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Try, Failure }

@ImplementedBy(classOf[SlackClientWrapperImpl])
trait SlackClientWrapper {
  def sendToSlack(webhook: SlackIncomingWebhook, msg: SlackMessageRequest): Future[Unit]
  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse]
  def addReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackMessageTimestamp): Future[Unit]
  def getChannelId(token: SlackAccessToken, channelName: SlackChannelName): Future[Option[SlackChannelId]]
  def getChannels(token: SlackAccessToken): Future[Seq[SlackChannelInfo]]
  def getChannelInfo(token: SlackAccessToken, channelId: SlackChannelId): Future[SlackChannelInfo]
  def getTeamInfo(token: SlackAccessToken): Future[SlackTeamInfo]
}

@Singleton
class SlackClientWrapperImpl @Inject() (
  db: Database,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  slackClient: SlackClient,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext)
    extends SlackClientWrapper with Logging {

  def sendToSlack(webhook: SlackIncomingWebhook, msg: SlackMessageRequest): Future[Unit] = {
    val now = clock.now
    slackClient.pushToWebhook(webhook.url, msg).andThen {
      case Success(()) =>
        db.readWrite { implicit s =>
          slackIncomingWebhookInfoRepo.getByWebhook(webhook).foreach { whi =>
            slackIncomingWebhookInfoRepo.save(whi.withCleanSlate.withLastPostedAt(now))
          }
        }
      case Failure(fail: SlackAPIFailure) =>
        log.error(s"[SLACK-CLIENT-WRAPPER] Caught a SlackAPIFailure ($fail) when posting, marking the webhook as broken")
        db.readWrite { implicit s =>
          slackIncomingWebhookInfoRepo.getByWebhook(webhook).foreach { whi =>
            slackIncomingWebhookInfoRepo.save(whi.withLastFailedAt(now).withLastFailure(fail))
          }
        }
      case Failure(other) =>
        log.error(s"[SLACK-CLIENT-WRAPPER] Caught a garbage error when posting, marking the webhook as broken")
    }
  }

  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse] = {
    slackClient.searchMessages(token, request).andThen(onRevokedToken(token))
  }

  def getChannels(token: SlackAccessToken): Future[Seq[SlackChannelInfo]] = {
    slackClient.getChannels(token).andThen(onRevokedToken(token))
  }
  def getChannelInfo(token: SlackAccessToken, channelId: SlackChannelId): Future[SlackChannelInfo] = {
    slackClient.getChannelInfo(token, channelId).andThen(onRevokedToken(token))
  }

  def addReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackMessageTimestamp): Future[Unit] = {
    slackClient.addReaction(token, reaction, channelId, messageTimestamp).andThen(onRevokedToken(token))
  }

  def getChannelId(token: SlackAccessToken, channelName: SlackChannelName): Future[Option[SlackChannelId]] = {
    slackClient.getChannelId(token, channelName).andThen(onRevokedToken(token))
  }

  private def onRevokedToken[T](token: SlackAccessToken): PartialFunction[Try[T], Unit] = {
    case Failure(fail @ SlackAPIFailure(_, SlackAPIFailure.Error.tokenRevoked, _)) =>
      db.readWrite { implicit s =>
        slackTeamMembershipRepo.getByToken(token).foreach { stm =>
          slackTeamMembershipRepo.save(stm.revoked)
        }
      }
  }

  def getTeamInfo(token: SlackAccessToken): Future[SlackTeamInfo] = {
    slackClient.getTeamInfo(token).andThen(onRevokedToken(token))
  }

}
