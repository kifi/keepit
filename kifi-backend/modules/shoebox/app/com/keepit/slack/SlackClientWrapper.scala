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
    slackClient.sendToSlack(webhook.url, msg).andThen {
      case Success(()) =>
        db.readWrite { implicit s =>
          slackIncomingWebhookInfoRepo.getByWebhook(webhook).foreach { whi =>
            slackIncomingWebhookInfoRepo.save(whi.withCleanSlate.withLastPostedAt(now))
          }
        }
      case Failure(fail: SlackAPIFailure) =>
        db.readWrite { implicit s =>
          slackIncomingWebhookInfoRepo.getByWebhook(webhook).foreach { whi =>
            slackIncomingWebhookInfoRepo.save(whi.withLastFailedAt(now).withLastFailure(fail))
          }
        }
      // TODO(ryan): until I figure out how to actually recognize SlackAPIFailures, we need to catch ALL failures and mark the webhook as busted
      // It would be nice to remove this at some point
      case Failure(other) =>
        db.readWrite { implicit s =>
          slackIncomingWebhookInfoRepo.getByWebhook(webhook).foreach { whi =>
            slackIncomingWebhookInfoRepo.save(whi.withLastFailedAt(now)) // Couldn't recognize the failure :(
          }
        }
    }
  }

  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse] = {
    slackClient.searchMessages(token, request).andThen(onRevokedToken(token))
  }

  def addReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackMessageTimestamp): Future[Unit] = {
    slackClient.addReaction(token, reaction, channelId, messageTimestamp).andThen(onRevokedToken(token))
  }

  private def onRevokedToken[T](token: SlackAccessToken): PartialFunction[Try[T], Unit] = {
    case Failure(fail @ SlackAPIFailure(_, SlackAPIFailure.Error.revokedToken, _)) =>
      db.readWrite { implicit s =>
        slackTeamMembershipRepo.getByToken(token).foreach { stm =>
          slackTeamMembershipRepo.save(stm.revoked)
        }
      }
  }

}
