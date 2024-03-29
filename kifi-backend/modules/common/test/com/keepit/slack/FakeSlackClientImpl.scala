package com.keepit.slack

import java.util.concurrent.atomic.AtomicLong

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.routes.GET
import com.keepit.slack.models._
import org.apache.commons.lang3.RandomStringUtils
import play.api.libs.json.Json
import com.keepit.common.time._

import scala.collection.mutable
import scala.concurrent.Future

case class FakeSlackClientModule() extends SlackClientModule {
  def configure() {}

  @Singleton
  @Provides
  def slackClient(): SlackClient = {
    new FakeSlackClientImpl()
  }
}

class FakeSlackClientImpl extends SlackClient {
  def checkUserPresence(token: SlackAccessToken, user: SlackUserId): Future[SlackUserPresence] = Future.successful(SlackUserPresence.UnknownPresence)
  val pushedMessagesByWebhook: mutable.Map[String, List[SlackMessageRequest]] = mutable.Map.empty.withDefaultValue(List.empty)
  val channelLog: mutable.Map[(SlackTeamId, SlackChannelName), List[SlackMessage]] = mutable.Map.empty.withDefaultValue(List.empty)
  val membershipToken: mutable.Map[SlackAccessToken, (SlackTeamId, SlackUserId)] = mutable.Map.empty
  val teamByToken: mutable.Map[SlackAccessToken, FullSlackTeamInfo] = mutable.Map.empty
  var isSlackDown = false
  var isSlackThrowingAFit = false
  private val inc = new AtomicLong(1442527939)

  val inChannelQuery = """in:([^ ]*)""".r

  def testToken(token: SlackAccessToken): Future[Unit] = Future.successful(())
  def identifyUser(token: SlackAccessToken): Future[SlackIdentifyResponse] = ???
  def getUserIdentity(token: SlackAccessToken): Future[SlackUserIdentityResponse] = ???
  def processAuthorizationResponse(code: SlackAuthorizationCode, redirectUri: String): Future[SlackAuthorizationResponse] = ???
  def pushToWebhook(url: String, msg: SlackMessageRequest): Future[Unit] = () match {
    case _ if isSlackDown => Future.failed(new Exception("slack_is_down"))
    case _ if isSlackThrowingAFit => Future.failed(SlackAPIErrorResponse.WebhookRevoked)
    case _ =>
      pushedMessagesByWebhook.put(url, msg :: pushedMessagesByWebhook(url))
      Future.successful(())
  }
  def postToChannel(token: SlackAccessToken, channelId: SlackChannelId, msg: SlackMessageRequest): Future[SlackMessageResponse] = ???
  def updateMessage(token: SlackAccessToken, channelId: SlackChannelId, timestamp: SlackTimestamp, msg: SlackMessageUpdateRequest): Future[SlackMessageResponse] = ???
  def deleteMessage(token: SlackAccessToken, channelId: SlackChannelId, timestamp: SlackTimestamp): Future[Unit] = ???

  def sayInChannel(userId: SlackUserId, username: SlackUsername, teamId: SlackTeamId, token: Option[SlackAccessToken], ch: (SlackChannelId, SlackChannelName))(str: String): Unit = {
    val channelIdAndName = SlackChannelIdAndName(ch._1, ch._2)
    val key = (teamId, channelIdAndName.name)
    val msgId = inc.incrementAndGet()
    val msgJson = Json.obj(
      "type" -> "message",
      "user" -> userId.value,
      "username" -> username.value,
      "ts" -> s"$msgId.00000",
      "channel" -> channelIdAndName,
      "text" -> str,
      "attachments" -> Json.arr(),
      "permalink" -> s"https://slack.com/$msgId"
    )
    val msg = msgJson.as[SlackMessage]
    token.foreach { tk => membershipToken.put(tk, teamId -> userId) }
    channelLog.put(key, msg :: channelLog(key))
  }
  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse] = () match {
    case _ if isSlackDown => Future.failed(new Exception("slack_is_down"))
    case _ if isSlackThrowingAFit => Future.failed(SlackAPIErrorResponse.TokenRevoked)
    case _ =>
      val bestGuessChannel = inChannelQuery.findAllMatchIn(request.query.query).toList.headOption.map(_.subgroups.head)
      val matches = (for {
        (teamId, userId) <- membershipToken.get(token)
        chName <- bestGuessChannel
        msgs <- channelLog.get((teamId, SlackChannelName(chName)))
      } yield msgs).getOrElse(List.empty)
      Future.successful(SlackSearchResponse(
        request.query,
        SlackSearchResponse.Messages(
          total = matches.length,
          paging = SlackSearchResponse.Paging(count = matches.length, total = matches.length, page = 1, pages = 1),
          matches = matches
        )
      ))
  }

  def addReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackTimestamp): Future[Unit] = Future.successful(())
  def getTeamInfo(token: SlackAccessToken): Future[FullSlackTeamInfo] = ???
  def getPublicChannels(token: SlackAccessToken, excludeArchived: Boolean): Future[Seq[SlackPublicChannelInfo]] = Future.successful(Seq.range(1, 10).map { x =>
    SlackPublicChannelInfo(
      SlackChannelId.Public("C" + RandomStringUtils.randomAlphanumeric(8)),
      SlackChannelName(if (x == 1) "#general" else RandomStringUtils.randomAlphabetic(15)),
      SlackUserId("U" + RandomStringUtils.randomAlphanumeric(8)),
      currentDateTime,
      isArchived = false,
      isGeneral = x == 1,
      members = Set.empty,
      topic = None,
      purpose = None
    )
  })
  def getPublicChannelInfo(token: SlackAccessToken, channelId: SlackChannelId): Future[SlackPublicChannelInfo] = ???
  def getPrivateChannels(token: SlackAccessToken, excludeArchived: Boolean): Future[Seq[SlackPrivateChannelInfo]] = ???
  def getPrivateChannelInfo(token: SlackAccessToken, channelId: SlackChannelId): Future[SlackPrivateChannelInfo] = ???
  def getUserInfo(token: SlackAccessToken, userId: SlackUserId): Future[FullSlackUserInfo] = ???
  def getUsers(token: SlackAccessToken): Future[Seq[FullSlackUserInfo]] = ???
  def getIMChannels(token: SlackAccessToken): Future[Seq[SlackIMChannelInfo]] = ???
  def getIMHistory(token: SlackAccessToken, channelId: SlackChannelId, fromTimestamp: Option[SlackTimestamp], limit: Int, inclusive: Boolean = false): Future[Seq[SlackHistoryMessage]] = ???
  def inviteToChannel(token: SlackAccessToken, invitee: SlackUserId, channelId: SlackChannelId): Future[Unit] = ???

}
