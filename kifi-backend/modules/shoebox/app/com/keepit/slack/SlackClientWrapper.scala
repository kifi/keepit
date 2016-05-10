package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ Logging, SlackLog }
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.common.util.{ Debouncing, Ord }
import com.keepit.model.User
import com.keepit.slack.models.SlackErrorCode._
import com.keepit.slack.models._

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[SlackClientWrapperImpl])
trait SlackClientWrapper {
  // These will potentially yield failed futures if the request cannot be completed

  def sendToSlackAsUser(userId: Id[User], slackTeamId: SlackTeamId, slackChannel: SlackChannelId, msg: SlackMessageRequest): Future[(SlackUserId, SlackMessageResponse)]
  def sendToSlackViaBot(slackTeamId: SlackTeamId, slackChannel: SlackChannelId, msg: SlackMessageRequest): Future[(SlackUserId, SlackMessageResponse)]
  def sendToSlackHoweverPossible(slackTeamId: SlackTeamId, slackChannel: SlackChannelId, msg: SlackMessageRequest): Future[Option[(SlackUserId, SlackMessageResponse)]]

  def updateMessage(token: SlackAccessToken, channelId: SlackChannelId, timestamp: SlackTimestamp, newMsg: SlackMessageUpdateRequest): Future[SlackMessageResponse]
  def deleteMessage(token: SlackAccessToken, channelId: SlackChannelId, timestamp: SlackTimestamp): Future[Unit]

  // PSA: validateToken recovers from SlackAPIFailures, it should always yield a successful future
  def validateToken(token: SlackAccessToken): Future[Boolean]

  // These APIs are token-specific
  def identifyUser(token: SlackAccessToken): Future[SlackIdentifyResponse]
  def getUserIdentity(token: SlackAccessToken): Future[SlackUserIdentityResponse]
  def processAuthorizationResponse(code: SlackAuthorizationCode, redirectUri: String): Future[SlackAuthorizationResponse]
  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse]
  def addReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackTimestamp): Future[Unit]
  def getPrivateChannels(token: SlackAccessToken, excludeArchived: Boolean = false): Future[Seq[SlackPrivateChannelInfo]]

  // These APIs are token-agnostic - will first try preferred tokens first then whichever they can find
  def getPublicChannels(slackTeamId: SlackTeamId, excludeArchived: Boolean = false, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[Seq[SlackPublicChannelInfo]]
  def getGeneralChannelId(slackTeamId: SlackTeamId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[Option[SlackChannelId]]
  def getPublicChannelInfo(slackTeamId: SlackTeamId, channelId: SlackChannelId.Public, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[SlackPublicChannelInfo]
  def getPrivateChannelInfo(slackTeamId: SlackTeamId, channelId: SlackChannelId.Private, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[SlackPrivateChannelInfo]
  def getTeamInfo(slackTeamId: SlackTeamId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[FullSlackTeamInfo]
  def getUserInfo(slackTeamId: SlackTeamId, userId: SlackUserId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[FullSlackUserInfo]
  def getUsers(slackTeamId: SlackTeamId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[Seq[FullSlackUserInfo]]
  def checkUserPresence(slackTeamId: SlackTeamId, user: SlackUserId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[SlackUserPresence]

  def getIMChannels(token: SlackAccessToken): Future[Seq[SlackIMChannelInfo]]
  def getIMHistory(token: SlackAccessToken, channelId: SlackChannelId, fromTimestamp: Option[SlackTimestamp], limit: Int): Future[Seq[SlackHistoryMessage]]

  // *Dangerous* - these APIs are only token-agnostic for a subset of parameters  
  def searchMessagesHoweverPossible(slackTeamId: SlackTeamId, request: SlackSearchRequest, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[SlackSearchResponse]
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
  implicit val executionContext: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends SlackClientWrapper with Logging {

  val debouncer = new Debouncing.Dropper[Unit]
  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SLACK)

  def sendToSlackHoweverPossible(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, msg: SlackMessageRequest): Future[Option[(SlackUserId, SlackMessageResponse)]] = {
    import SlackErrorCode._
    sendToSlackViaBot(slackTeamId, slackChannelId, msg).map(v => Some(v)).recoverWith {
      case SlackFail.NoValidBotToken | SlackErrorCode(CHANNEL_NOT_FOUND) | SlackErrorCode(NOT_IN_CHANNEL) | SlackErrorCode(IS_ARCHIVED) =>
        val slackTeamMembers = db.readOnlyMaster { implicit s =>
          slackTeamMembershipRepo.getBySlackTeam(slackTeamId).map(_.slackUserId)
        }
        FutureHelpers.collectFirst(slackTeamMembers) { slackUserId =>
          pushToSlackUsingToken(slackUserId, slackTeamId, slackChannelId, msg).map(v => Some(slackUserId, v)).recover {
            case SlackFail.NoValidToken | SlackErrorCode(NOT_IN_CHANNEL) | SlackErrorCode(CHANNEL_NOT_FOUND) | SlackErrorCode(IS_ARCHIVED) | SlackErrorCode(RESTRICTED_ACTION) => None
          }
        }.flatMap {
          case Some(v) => Future.successful(Some(v))
          case None =>
            pushToSlackViaWebhook(slackTeamId, slackChannelId, msg).map(_ => None).recoverWith {
              case SlackFail.NoValidWebhooks => Future.failed(SlackFail.NoValidPushMethod)
            }
        }
    }
  }

  def sendToSlackViaBot(slackTeamId: SlackTeamId, slackChannel: SlackChannelId, msg: SlackMessageRequest): Future[(SlackUserId, SlackMessageResponse)] = {
    val bot = db.readOnlyMaster { implicit s =>
      slackTeamRepo.getBySlackTeamId(slackTeamId).flatMap(_.kifiBot)
    }
    bot match {
      case None => Future.failed(SlackFail.NoValidBotToken)
      case Some(kifiBot) =>
        slackClient.postToChannel(kifiBot.token, slackChannel, msg.fromUser).andThen(onRevokedBotToken(kifiBot.token))
          .recoverWith {
            case botFail @ SlackErrorCode(NOT_IN_CHANNEL) =>
              inviteToChannel(kifiBot.userId, slackTeamId, slackChannel).flatMap { _ =>
                slackClient.postToChannel(kifiBot.token, slackChannel, msg.fromUser)
              }.recoverWith {
                case fail =>
                  slackLog.error(s"Could not invite kifi-bot to $slackChannel in $slackTeamId", fail.getMessage)
                  Future.failed(botFail)
              }
          }
          .imap(kifiBot.userId -> _)
    }
  }
  def sendToSlackAsUser(userId: Id[User], slackTeamId: SlackTeamId, slackChannel: SlackChannelId, msg: SlackMessageRequest): Future[(SlackUserId, SlackMessageResponse)] = {
    val idAndToken = db.readOnlyMaster { implicit s =>
      slackTeamMembershipRepo.getByUserIdAndSlackTeam(userId, slackTeamId).flatMap(stm => stm.getTokenIncludingScopes(Set(SlackAuthScope.ChatWriteUser)).map(stm.slackUserId -> _))
    }
    idAndToken match {
      case None => Future.failed(SlackFail.NoValidToken)
      case Some((slackUserId, validToken)) =>
        slackClient.postToChannel(validToken, slackChannel, msg.fromUser).andThen(onRevokedUserToken(validToken)).imap(slackUserId -> _)
    }
  }
  def inviteToChannel(invitee: SlackUserId, teamId: SlackTeamId, channelId: SlackChannelId): Future[Unit] = {
    withFirstValidToken(teamId, preferredTokens = Seq.empty, requiredScopes = Set(SlackAuthScope.ChannelsWrite)) { token =>
      slackClient.inviteToChannel(token, invitee, channelId)
    }
  }

  private def pushToSlackViaWebhook(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, msg: SlackMessageRequest): Future[Unit] = {
    val webhooks = db.readOnlyMaster { implicit s =>
      slackIncomingWebhookInfoRepo.getByChannel(slackTeamId, slackChannelId)
    }
    FutureHelpers.exists(webhooks) { webhookInfo =>
      val now = clock.now
      val pushFut = slackClient.pushToWebhook(webhookInfo.url, msg).andThen {
        case Success(_: Unit) => db.readWrite { implicit s =>
          slackChannelRepo.getByChannelId(webhookInfo.slackTeamId, webhookInfo.slackChannelId).foreach { channel =>
            slackChannelRepo.save(channel.withLastNotificationAtLeast(now))
          }
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
    }.flatMap {
      case true => Future.successful(())
      case false => Future.failed(SlackFail.NoValidWebhooks)
    }
  }

  private def pushToSlackUsingToken(slackUserId: SlackUserId, slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, msg: SlackMessageRequest): Future[SlackMessageResponse] = {
    val workingToken = db.readOnlyMaster { implicit s =>
      slackTeamMembershipRepo.getBySlackTeamAndUser(slackTeamId, slackUserId).flatMap(_.getTokenIncludingScopes(Set(SlackAuthScope.ChatWriteBot)))
    }
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

  def updateMessage(token: SlackAccessToken, channelId: SlackChannelId, timestamp: SlackTimestamp, newMsg: SlackMessageUpdateRequest): Future[SlackMessageResponse] = {
    slackClient.updateMessage(token, channelId, timestamp, newMsg)
  }
  def deleteMessage(token: SlackAccessToken, channelId: SlackChannelId, timestamp: SlackTimestamp): Future[Unit] = {
    slackClient.deleteMessage(token, channelId, timestamp)
  }

  def validateToken(token: SlackAccessToken): Future[Boolean] = {
    slackClient.testToken(token).andThen(onRevokedToken(token)).map(_ => true).recover { case f => false }
  }

  def identifyUser(token: SlackAccessToken): Future[SlackIdentifyResponse] = {
    slackClient.identifyUser(token)
  }

  def getUserIdentity(token: SlackAccessToken): Future[SlackUserIdentityResponse] = {
    slackClient.getUserIdentity(token)
  }

  def processAuthorizationResponse(code: SlackAuthorizationCode, redirectUri: String): Future[SlackAuthorizationResponse] = {
    slackClient.processAuthorizationResponse(code, redirectUri)
  }

  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse] = {
    slackClient.searchMessages(token, request).andThen(onRevokedToken(token))
  }

  def addReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackTimestamp): Future[Unit] = {
    slackClient.addReaction(token, reaction, channelId, messageTimestamp).andThen(onRevokedToken(token))
  }

  def getPrivateChannels(token: SlackAccessToken, excludeArchived: Boolean = false): Future[Seq[SlackPrivateChannelInfo]] = {
    slackClient.getPrivateChannels(token, excludeArchived) andThen (onRevokedToken(token)) andThen {
      case Success(chs) => db.readWrite { implicit s =>
        getSlackTeamId(token).foreach { teamId =>
          chs.foreach { ch =>
            slackChannelRepo.getOrCreate(teamId, ch.channelId, ch.channelName)
          }
        }
      }
    }
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
    case Failure(SlackErrorCode(TOKEN_REVOKED) | SlackErrorCode(ACCOUNT_INACTIVE)) =>
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
        slackTeamRepo.getBySlackTeamId(slackTeamId).flatMap(_.kifiBot.map(_.token)).filter(!preferredTokens.contains(_))
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

  def getPublicChannels(slackTeamId: SlackTeamId, excludeArchived: Boolean = false, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[Seq[SlackPublicChannelInfo]] = {
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
    getPublicChannels(slackTeamId, preferredTokens = preferredTokens).imap(_.collectFirst { case channel if channel.isGeneral => channel.channelId })
  }

  def getPublicChannelInfo(slackTeamId: SlackTeamId, channelId: SlackChannelId.Public, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[SlackPublicChannelInfo] = {
    withFirstValidToken(slackTeamId, preferredTokens, Set(SlackAuthScope.ChannelsRead)) { token =>
      slackClient.getPublicChannelInfo(token, channelId) andThen {
        case Success(channel) => db.readWrite { implicit s =>
          slackChannelRepo.getOrCreate(slackTeamId, channelId, channel.channelName)
        }
      }
    }
  }

  def getPrivateChannelInfo(slackTeamId: SlackTeamId, channelId: SlackChannelId.Private, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[SlackPrivateChannelInfo] = {
    withFirstValidToken(slackTeamId, preferredTokens, Set(SlackAuthScope.GroupsRead)) { token =>
      slackClient.getPrivateChannelInfo(token, channelId) andThen {
        case Success(channel) => db.readWrite { implicit s =>
          slackChannelRepo.getOrCreate(slackTeamId, channelId, channel.channelName)
        }
      }
    }
  }

  def getTeamInfo(slackTeamId: SlackTeamId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[FullSlackTeamInfo] = {
    withFirstValidToken(slackTeamId, preferredTokens, Set(SlackAuthScope.TeamRead)) { token =>
      slackClient.getTeamInfo(token) andThen {
        case Success(team) => db.readWrite { implicit s =>
          slackTeamRepo.internSlackTeam(slackTeamId, team.name, None)
        }
      }
    }
  }

  def getUserInfo(slackTeamId: SlackTeamId, userId: SlackUserId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[FullSlackUserInfo] = {
    withFirstValidToken(slackTeamId, preferredTokens, Set(SlackAuthScope.UsersRead)) { token =>
      slackClient.getUserInfo(token, userId) andThen {
        case Success(user) => db.readWrite { implicit s =>
          slackTeamRepo.getBySlackTeamId(slackTeamId).foreach { slackTeam =>
            saveUserInfo(slackTeam.slackTeamId, user)
          }
        }
      }
    }
  }

  def getUsers(slackTeamId: SlackTeamId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[Seq[FullSlackUserInfo]] = {
    withFirstValidToken(slackTeamId, preferredTokens, Set(SlackAuthScope.UsersRead)) { token =>
      slackClient.getUsers(token) andThen {
        case Success(users) =>
          db.readOnlyMaster { implicit s => slackTeamRepo.getBySlackTeamId(slackTeamId) }.map(_.slackTeamId).foreach { teamId =>
            // There can be a TON of users here, so don't block the request trying to intern them all
            FutureHelpers.sequentialExec(users) { user =>
              db.readWriteAsync { implicit s => saveUserInfo(teamId, user) }
            }
          }
      }
    }
  }

  def getIMChannels(token: SlackAccessToken): Future[Seq[SlackIMChannelInfo]] = {
    slackClient.getIMChannels(token)
  }
  def getIMHistory(token: SlackAccessToken, channelId: SlackChannelId, fromTimestamp: Option[SlackTimestamp], limit: Int): Future[Seq[SlackHistoryMessage]] = {
    slackClient.getIMHistory(token, channelId, fromTimestamp, limit, inclusive = false)
  }

  private def saveUserInfo(slackTeamId: SlackTeamId, user: FullSlackUserInfo)(implicit session: RWSession): SlackTeamMembership = {
    slackTeamMembershipRepo.internMembership(SlackTeamMembershipInternRequest(
      userId = None,
      slackUserId = user.id,
      slackTeamId = slackTeamId,
      tokenWithScopes = None,
      slackUser = Some(user)
    ))._1
  }

  def checkUserPresence(slackTeamId: SlackTeamId, user: SlackUserId, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[SlackUserPresence] = {
    withFirstValidToken(slackTeamId, preferredTokens, Set(SlackAuthScope.UsersRead)) { token =>
      slackClient.checkUserPresence(token, user)
    }
  }

  def searchMessagesHoweverPossible(slackTeamId: SlackTeamId, request: SlackSearchRequest, preferredTokens: Seq[SlackAccessToken] = Seq.empty): Future[SlackSearchResponse] = {
    withFirstValidToken(slackTeamId, preferredTokens, Set(SlackAuthScope.SearchRead)) { token =>
      slackClient.searchMessages(token, request)
    }
  }

}
