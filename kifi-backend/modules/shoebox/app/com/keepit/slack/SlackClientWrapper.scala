package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.slack.models._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Try, Failure }

sealed abstract class SlackChannelMagnet
object SlackChannelMagnet {
  case class Id(id: SlackChannelId) extends SlackChannelMagnet
  implicit def fromId(id: SlackChannelId): SlackChannelMagnet = Id(id)

  case class Name(name: SlackChannelName) extends SlackChannelMagnet
  implicit def fromName(name: SlackChannelName): SlackChannelMagnet = Name(name)

  case class NameAndId(name: SlackChannelName, id: SlackChannelId) extends SlackChannelMagnet
  implicit def fromBoth(nameAndId: (SlackChannelName, SlackChannelId)): SlackChannelMagnet =
    NameAndId(nameAndId._1, nameAndId._2)

  implicit def fromNameAndMaybeId(nameAndMaybeId: (SlackChannelName, Option[SlackChannelId])): SlackChannelMagnet =
    nameAndMaybeId match {
      case (name, Some(id)) => fromBoth(name, id)
      case (name, None) => fromName(name)
    }
}

@ImplementedBy(classOf[SlackClientWrapperImpl])
trait SlackClientWrapper {
  def sendToSlack(slackUserId: SlackUserId, slackTeamId: SlackTeamId, slackChannel: SlackChannelMagnet, msg: SlackMessageRequest): Future[Unit]
  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse]
  def addReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackTimestamp): Future[Unit]
  def getChannelId(token: SlackAccessToken, channelName: SlackChannelName): Future[Option[SlackChannelId]]
  def getChannels(token: SlackAccessToken, excludeArchived: Boolean = false): Future[Seq[SlackChannelInfo]]
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

  def sendToSlack(slackUserId: SlackUserId, slackTeamId: SlackTeamId, slackChannel: SlackChannelMagnet, msg: SlackMessageRequest): Future[Unit] = {
    slackChannel match {
      case SlackChannelMagnet.Name(name) =>
        pushToSlackViaWebhook(slackUserId, slackTeamId, name, msg)
      case SlackChannelMagnet.Id(id) =>
        pushToSlackUsingToken(slackUserId, slackTeamId, id, msg)
      case SlackChannelMagnet.NameAndId(name, id) =>
        pushToSlackViaWebhook(slackUserId, slackTeamId, name, msg).recoverWith {
          case _ =>
            pushToSlackUsingToken(slackUserId, slackTeamId, id, msg)
        }
    }
  }

  private def pushToSlackViaWebhook(slackUserId: SlackUserId, slackTeamId: SlackTeamId, slackChannelName: SlackChannelName, msg: SlackMessageRequest): Future[Unit] = {
    FutureHelpers.doUntilAttempts {
      val firstWorkingWebhook = db.readOnlyMaster { implicit s =>
        slackIncomingWebhookInfoRepo.getForChannelByName(slackUserId, slackTeamId, slackChannelName).headOption
      }
      firstWorkingWebhook match {
        case None => Future.failed(SlackAPIFailure.NoValidWebhooks)
        case Some(webhookInfo) =>
          val now = clock.now
          val pushFut = slackClient.pushToWebhook(webhookInfo.webhook.url, msg).andThen {
            case Success(_: Unit) => db.readWrite { implicit s =>
              slackIncomingWebhookInfoRepo.save(
                slackIncomingWebhookInfoRepo.get(webhookInfo.id.get).withCleanSlate.withLastPostedAt(now)
              )
            }
            case Failure(fail: SlackAPIFailure) => db.readWrite { implicit s =>
              slackIncomingWebhookInfoRepo.save(
                slackIncomingWebhookInfoRepo.get(webhookInfo.id.get).withLastFailedAt(now).withLastFailure(fail)
              )
            }
            case Failure(other) =>
              airbrake.notify("Got an unparseable error while pushing to Slack.", other)
          }

          pushFut.map(_ => true).recover { case fail: SlackAPIFailure => false }
      }
    }
  }

  private def pushToSlackUsingToken(slackUserId: SlackUserId, slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, msg: SlackMessageRequest): Future[Unit] = {
    FutureHelpers.doUntilAttempts {
      val workingToken = db.readOnlyMaster { implicit s =>
        slackTeamMembershipRepo.getBySlackTeamAndUser(slackTeamId, slackUserId).collect {
          case stm if stm.token.isDefined && stm.scopes.contains(SlackAuthScope.ChatWriteBot) => stm.token.get
        }
      }
      workingToken match {
        case None => Future.failed(SlackAPIFailure.NoValidToken)
        case Some(token) =>
          val pushFut = slackClient.postToChannel(token, slackChannelId, msg).andThen {
            case Success(_: Unit) => // If we ever kept track of successes for tokens, here is where we would note the success
            case Failure(fail: SlackAPIFailure) => db.readWrite { implicit s =>
              slackTeamMembershipRepo.getByToken(token).foreach { stm =>
                slackTeamMembershipRepo.save(stm.revoked)
              }
            }
            case Failure(other) =>
              airbrake.notify("Got an unparseable error while pushing to Slack.", other)
          }

          pushFut.map(_ => true).recover { case fail: SlackAPIFailure => false }
      }
    }
  }

  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse] = {
    slackClient.searchMessages(token, request).andThen(onRevokedToken(token))
  }

  def getChannels(token: SlackAccessToken, excludeArchived: Boolean = false): Future[Seq[SlackChannelInfo]] = {
    slackClient.getChannels(token, excludeArchived).andThen(onRevokedToken(token))
  }
  def getChannelInfo(token: SlackAccessToken, channelId: SlackChannelId): Future[SlackChannelInfo] = {
    slackClient.getChannelInfo(token, channelId).andThen(onRevokedToken(token))
  }

  def addReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackTimestamp): Future[Unit] = {
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
