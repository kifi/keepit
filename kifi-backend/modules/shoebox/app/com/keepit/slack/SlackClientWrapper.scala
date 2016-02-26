package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.slack.models.SlackErrorCode._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.common.util.{ Debouncing, Ord }
import com.keepit.slack.models._
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Try, Failure }
import scala.concurrent.duration._

sealed abstract class SlackChannelMagnet
object SlackChannelMagnet {
  case class Id(id: SlackChannelId) extends SlackChannelMagnet
  implicit def fromId(id: SlackChannelId): SlackChannelMagnet = Id(id)

  case class Name(name: SlackChannelName) extends SlackChannelMagnet
  implicit def fromName(name: SlackChannelName): SlackChannelMagnet = Name(name)

  case class NameAndId(name: SlackChannelName, id: SlackChannelId) extends SlackChannelMagnet
  implicit def fromBoth(idAndName: (SlackChannelId, SlackChannelName)): SlackChannelMagnet =
    NameAndId(idAndName._2, idAndName._1)

  implicit def fromNameAndMaybeId(nameAndMaybeId: (SlackChannelName, Option[SlackChannelId])): SlackChannelMagnet =
    nameAndMaybeId match {
      case (name, Some(id)) => fromBoth(id, name)
      case (name, None) => fromName(name)
    }
}

@ImplementedBy(classOf[SlackClientWrapperImpl])
trait SlackClientWrapper {
  def sendToSlackViaUser(slackUserId: SlackUserId, slackTeamId: SlackTeamId, slackChannel: SlackChannelMagnet, msg: SlackMessageRequest): Future[Option[SlackMessageResponse]]
  def sendToSlackHoweverPossible(slackTeamId: SlackTeamId, slackChannel: SlackChannelId, msg: SlackMessageRequest): Future[Option[SlackMessageResponse]]
  def sendToSlackViaBot(slackTeamId: SlackTeamId, slackChannel: SlackChannelId, msg: SlackMessageRequest): Future[SlackMessageResponse]

  def updateMessage(token: SlackAccessToken, channelId: SlackChannelId, timestamp: SlackTimestamp, newMsg: SlackMessageRequest): Future[SlackMessageResponse]
  def deleteMessage(token: SlackAccessToken, channelId: SlackChannelId, timestamp: SlackTimestamp): Future[Unit]

  // PSA: validateToken recovers from SlackAPIFailures, it should always yield a successful future
  def validateToken(token: SlackAccessToken): Future[Boolean]

  // These will potentially yield failed futures if the request cannot be completed
  def searchMessages(token: SlackUserAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse]
  def addReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackTimestamp): Future[Unit]
  def getChannelId(token: SlackAccessToken, channelName: SlackChannelName): Future[Option[SlackChannelId]]

  // These are APIs token-agnostic - will first try preferred tokens first then whichever they can find
  def getChannels(slackTeamId: SlackTeamId, excludeArchived: Boolean = false, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[Seq[SlackPublicChannelInfo]]
  def getGeneralChannelId(slackTeamId: SlackTeamId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[Option[SlackChannelId]]
  def getChannelInfo(slackTeamId: SlackTeamId, channelId: SlackChannelId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[SlackPublicChannelInfo]
  def getTeamInfo(slackTeamId: SlackTeamId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[SlackTeamInfo]
  def getUserInfo(slackTeamId: SlackTeamId, userId: SlackUserId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[SlackUserInfo]
  def getUsers(slackTeamId: SlackTeamId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[Seq[SlackUserInfo]]

}

@Singleton
class SlackClientWrapperImpl @Inject() (
  db: Database,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  slackChannelRepo: SlackChannelRepo,
  slackTeamRepo: SlackTeamRepo,
  channelToLibraryRepo: SlackChannelToLibraryRepo,
  libraryToChannelRepo: LibraryToSlackChannelRepo,
  slackClient: SlackClient,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext)
    extends SlackClientWrapper with Logging {

  val debouncer = new Debouncing.Dropper[Unit]

  def sendToSlackViaUser(slackUserId: SlackUserId, slackTeamId: SlackTeamId, slackChannel: SlackChannelMagnet, msg: SlackMessageRequest): Future[Option[SlackMessageResponse]] = {
    slackChannel match {
      case SlackChannelMagnet.Name(name) =>
        pushToSlackViaWebhook(slackUserId, slackTeamId, name, msg).map(_ => None)
      case SlackChannelMagnet.Id(id) =>
        pushToSlackUsingToken(slackUserId, slackTeamId, id, msg).map(v => Some(v))
      case SlackChannelMagnet.NameAndId(name, id) =>
        pushToSlackViaWebhook(slackUserId, slackTeamId, name, msg).map(_ => None).recoverWith {
          case _ => pushToSlackUsingToken(slackUserId, slackTeamId, id, msg).map(v => Some(v))
        }
    }
  }

  def sendToSlackHoweverPossible(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, msg: SlackMessageRequest): Future[Option[SlackMessageResponse]] = {
    import SlackErrorCode._
    sendToSlackViaBot(slackTeamId, slackChannelId, msg).map(v => Some(v)).recoverWith {
      case SlackFail.NoValidBotToken | SlackErrorCode(CHANNEL_NOT_FOUND) | SlackErrorCode(NOT_IN_CHANNEL) =>
        val (slackChannel, slackTeamMembers) = db.readOnlyMaster { implicit s =>
          val slackChannel = slackChannelRepo.getByChannelId(slackTeamId, slackChannelId)
          val memberships = slackTeamMembershipRepo.getBySlackTeam(slackTeamId).map(_.slackUserId)
          (slackChannel, memberships)
        }
        FutureHelpers.collectFirst(slackTeamMembers) { slackUserId =>
          sendToSlackViaUser(slackUserId, slackTeamId, slackChannel.map(ch => SlackChannelMagnet.fromBoth(ch.idAndName)) getOrElse slackChannelId, msg).map(v => Some(v)).recover {
            case SlackFail.NoValidWebhooks | SlackFail.NoValidToken | SlackErrorCode(NOT_IN_CHANNEL) | SlackErrorCode(CHANNEL_NOT_FOUND) | SlackErrorCode(RESTRICTED_ACTION) => None
          }
        }.flatMap {
          case Some(v) => Future.successful(v)
          case None => Future.failed(SlackFail.NoValidPushMethod)
        }
    }
  }

  def sendToSlackViaBot(slackTeamId: SlackTeamId, slackChannel: SlackChannelId, msg: SlackMessageRequest): Future[SlackMessageResponse] = {
    val botToken = db.readOnlyMaster { implicit s =>
      slackTeamRepo.getBySlackTeamId(slackTeamId).flatMap(_.kifiBotToken)
    }
    botToken match {
      case Some(token) => slackClient.postToChannel(token, slackChannel, msg.fromUser).andThen(onRevokedBotToken(token))
      case None => Future.failed(SlackFail.NoValidBotToken)
    }
  }

  private def pushToSlackViaWebhook(slackUserId: SlackUserId, slackTeamId: SlackTeamId, slackChannelName: SlackChannelName, msg: SlackMessageRequest): Future[Unit] = {
    FutureHelpers.doUntil {
      val firstWorkingWebhook = db.readOnlyMaster { implicit s =>
        slackIncomingWebhookInfoRepo.getForChannelByName(slackUserId, slackTeamId, slackChannelName).headOption
      }
      firstWorkingWebhook match {
        case None => Future.failed(SlackFail.NoValidWebhooks)
        case Some(webhookInfo) =>
          val now = clock.now
          val pushFut = slackClient.pushToWebhook(webhookInfo.webhook.url, msg).andThen {
            case Success(_: Unit) => db.readWrite { implicit s =>
              for {
                channelId <- webhookInfo.webhook.channelId
                channel <- slackChannelRepo.getByChannelId(webhookInfo.slackTeamId, channelId)
              } slackChannelRepo.save(channel.withLastNotificationAtLeast(now))

              slackIncomingWebhookInfoRepo.save(
                slackIncomingWebhookInfoRepo.get(webhookInfo.id.get).withCleanSlate.withLastPostedAt(now)
              )
            }
            case Failure(fail: SlackAPIErrorResponse) => db.readWrite { implicit s =>
              slackIncomingWebhookInfoRepo.save(
                slackIncomingWebhookInfoRepo.get(webhookInfo.id.get).withLastFailedAt(now).withLastFailure(fail)
              )
            }
            case Failure(other) =>
              airbrake.notify("Got an unparseable error while pushing to Slack.", other)
          }

          pushFut.map(_ => true).recover { case fail: SlackAPIErrorResponse => false }
      }
    }
  }

  private def pushToSlackUsingToken(slackUserId: SlackUserId, slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, msg: SlackMessageRequest): Future[SlackMessageResponse] = {
    val workingToken = db.readOnlyMaster { implicit s =>
      slackTeamMembershipRepo.getBySlackTeamAndUser(slackTeamId, slackUserId).collect {
        case SlackTokenWithScopes(token, scopes) if scopes.contains(SlackAuthScope.ChatWriteBot) => token
      }
    }
    log.info(s"[SLACK-CLIENT-WRAPPER] Pushing to $slackChannelId in $slackTeamId from $slackUserId and using $workingToken")
    workingToken match {
      case None => Future.failed(SlackFail.NoValidToken)
      case Some(token) =>
        val now = clock.now
        slackClient.postToChannel(token, slackChannelId, msg).andThen(onRevokedToken(token)).andThen {
          case Success(_) =>
            db.readWrite { implicit s =>
              slackChannelRepo.getByChannelId(slackTeamId, slackChannelId).foreach { channel =>
                slackChannelRepo.save(channel.withLastNotificationAtLeast(now))
              }
            }
        }
    }
  }

  def updateMessage(token: SlackAccessToken, channelId: SlackChannelId, timestamp: SlackTimestamp, newMsg: SlackMessageRequest): Future[SlackMessageResponse] = {
    slackClient.updateMessage(token, channelId, timestamp, newMsg)
  }
  def deleteMessage(token: SlackAccessToken, channelId: SlackChannelId, timestamp: SlackTimestamp): Future[Unit] = {
    slackClient.deleteMessage(token, channelId, timestamp)
  }

  def validateToken(token: SlackAccessToken): Future[Boolean] = {
    slackClient.testToken(token).andThen(onRevokedToken(token)).map(_ => true).recover { case f => false }
  }

  def searchMessages(token: SlackUserAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse] = {
    slackClient.searchMessages(token, request).andThen(onRevokedToken(token))
  }

  def addReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackTimestamp): Future[Unit] = {
    slackClient.addReaction(token, reaction, channelId, messageTimestamp).andThen(onRevokedToken(token))
  }

  def getChannelId(token: SlackAccessToken, channelName: SlackChannelName): Future[Option[SlackChannelId]] = {
    slackClient.getChannelId(token, channelName).andThen(onRevokedToken(token))
  }

  private def getSlackTeamId(token: SlackAccessToken)(implicit session: RSession): Option[SlackTeamId] = {
    token match {
      case userToken: SlackUserAccessToken => slackTeamMembershipRepo.getByToken(userToken).map(_.slackTeamId)
      case botToken: SlackBotAccessToken => slackTeamRepo.getByKifiBotToken(botToken).map(_.slackTeamId)
    }
  }

  private def onRevokedToken[T](token: SlackAccessToken): PartialFunction[Try[T], Unit] = {
    token match {
      case userToken: SlackUserAccessToken => onRevokedUserToken(userToken)
      case botToken: SlackBotAccessToken => onRevokedBotToken(botToken)
    }
  }

  private def onRevokedUserToken[T](token: SlackUserAccessToken): PartialFunction[Try[T], Unit] = {
    case Failure(SlackErrorCode(TOKEN_REVOKED)) => db.readWrite { implicit s =>
      slackTeamMembershipRepo.getByToken(token).foreach { stm =>
        slackTeamMembershipRepo.save(stm.revoked)
      }
    }
    case Failure(SlackErrorCode(ACCOUNT_INACTIVE)) => db.readWrite { implicit s =>
      slackTeamMembershipRepo.getByToken(token).foreach { stm =>
        slackTeamMembershipRepo.deactivate(stm)
      }
    }
    case Failure(otherFail) => debouncer.debounce("after-token", 5 seconds) { airbrake.notify(otherFail) }
  }

  private def onRevokedBotToken[T](token: SlackBotAccessToken): PartialFunction[Try[T], Unit] = {
    case Failure(SlackErrorCode(TOKEN_REVOKED)) =>
      db.readWrite { implicit s =>
        slackTeamRepo.getByKifiBotToken(token).foreach { slackTeam =>
          slackTeamRepo.save(slackTeam.withNoKifiBot)
          log.warn(s"[SLACK-CLIENT-WRAPPER] Kifi-bot was killed in ${slackTeam.slackTeamName.value} ${slackTeam.slackTeamId}")
        }
      }
  }

  private def withFirstValidToken[T](slackTeamId: SlackTeamId, preferredTokens: Seq[SlackAccessToken], requiredScopes: Set[SlackAuthScope])(f: SlackAccessToken => Future[T]): Future[T] = {
    val tokens: Stream[SlackAccessToken] = {
      lazy val botTokenOpt = if (requiredScopes subsetOf SlackAuthScope.inheritableBotScopes) db.readOnlyMaster { implicit session =>
        slackTeamRepo.getBySlackTeamId(slackTeamId).flatMap(_.kifiBotToken)
      }
      else None
      lazy val userTokens = db.readOnlyMaster { implicit session =>
        val memberships = slackTeamMembershipRepo.getBySlackTeam(slackTeamId).toSeq.sortBy(_.updatedAt.getMillis)(Ord.descending)
        memberships.flatMap(_.getTokenIncludingScopes(requiredScopes)).filter(!preferredTokens.contains(_))
      }
      preferredTokens.toStream append botTokenOpt append userTokens
    }
    FutureHelpers.collectFirst(tokens) { token =>
      f(token).map(Some(_)) andThen onRevokedToken(token) recover { case fail: SlackAPIErrorResponse => None }
    }
  }.flatMap(_.map(Future.successful(_)).getOrElse(Future.failed(SlackFail.NoValidToken)))

  def getChannels(slackTeamId: SlackTeamId, excludeArchived: Boolean = false, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[Seq[SlackPublicChannelInfo]] = {
    withFirstValidToken(slackTeamId, preferredTokens, Set(SlackAuthScope.ChannelsRead)) { token =>
      slackClient.getPublicChannels(token, excludeArchived) andThen {
        case Success(chs) => db.readWrite { implicit s =>
          getSlackTeamId(token).foreach { teamId =>
            chs.foreach { ch =>
              slackChannelRepo.getOrCreate(teamId, ch.channelId, ch.channelName)
            }
          }
        }
      }
    }
  }

  def getGeneralChannelId(slackTeamId: SlackTeamId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[Option[SlackChannelId]] = {
    getChannels(slackTeamId, preferredTokens = preferredTokens).imap(_.collectFirst { case channel if channel.isGeneral => channel.channelId })
  }

  def getChannelInfo(slackTeamId: SlackTeamId, channelId: SlackChannelId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[SlackPublicChannelInfo] = {
    withFirstValidToken(slackTeamId, preferredTokens, Set(SlackAuthScope.ChannelsRead)) { token =>
      slackClient.getPublicChannelInfo(token, channelId)
    }
  }

  def getTeamInfo(slackTeamId: SlackTeamId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[SlackTeamInfo] = {
    withFirstValidToken(slackTeamId, preferredTokens, Set(SlackAuthScope.TeamRead)) { token =>
      slackClient.getTeamInfo(token)
    }
  }

  def getUserInfo(slackTeamId: SlackTeamId, userId: SlackUserId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[SlackUserInfo] = {
    withFirstValidToken(slackTeamId, preferredTokens, Set(SlackAuthScope.UsersRead)) { token =>
      slackClient.getUserInfo(token, userId)
    }
  }

  def getUsers(slackTeamId: SlackTeamId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[Seq[SlackUserInfo]] = {
    withFirstValidToken(slackTeamId, preferredTokens, Set(SlackAuthScope.UsersRead)) { token =>
      slackClient.getUsers(token)
    }
  }
}
