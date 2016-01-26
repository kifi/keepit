package com.keepit.slack

import java.util.concurrent.atomic.AtomicLong

import com.google.inject.{ Provides, Singleton }
import com.keepit.slack.models._

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
  val pushedMessagesByWebhook: mutable.Map[String, List[SlackMessageRequest]] = mutable.Map.empty.withDefaultValue(List.empty)
  val channelLog: mutable.Map[(SlackTeamId, SlackChannelName), List[SlackMessage]] = mutable.Map.empty.withDefaultValue(List.empty)
  val membershipToken: mutable.Map[SlackAccessToken, (SlackTeamId, SlackUserId)] = mutable.Map.empty
  val teamByToken: mutable.Map[SlackAccessToken, SlackTeamInfo] = mutable.Map.empty
  var isSlackDown = false
  var isSlackThrowingAFit = false
  private val inc = new AtomicLong(1442527939)

  val inChannelQuery = """in:([^ ]*)""".r

  def validateToken(token: SlackAccessToken): Future[Boolean] = ???
  def identifyUser(token: SlackAccessToken): Future[SlackIdentifyResponse] = ???
  def processAuthorizationResponse(code: SlackAuthorizationCode, redirectUri: String): Future[SlackAuthorizationResponse] = ???
  def pushToWebhook(url: String, msg: SlackMessageRequest): Future[Unit] = () match {
    case _ if isSlackDown => Future.failed(new Exception("slack_is_down"))
    case _ if isSlackThrowingAFit => Future.failed(SlackAPIFailure.WebhookRevoked)
    case _ =>
      pushedMessagesByWebhook.put(url, msg :: pushedMessagesByWebhook(url))
      Future.successful(())
  }
  def postToChannel(token: SlackAccessToken, channelId: SlackChannelId, msg: SlackMessageRequest): Future[Unit] = ???

  def sayInChannel(userId: SlackUserId, username: SlackUsername, teamId: SlackTeamId, token: Option[SlackAccessToken], ch: SlackChannelIdAndName)(str: String): Unit = {
    val key = (teamId, ch.name)
    val msgId = inc.incrementAndGet()
    val msg = SlackMessage(
      messageType = SlackMessageType("message"),
      userId = userId,
      username = username,
      timestamp = SlackTimestamp(s"$msgId.00000"),
      channel = ch,
      text = str,
      attachments = Seq.empty,
      permalink = s"https://slack.com/$msgId"
    )
    token.foreach { tk => membershipToken.put(tk, teamId -> userId) }
    channelLog.put(key, msg :: channelLog(key))
  }
  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse] = () match {
    case _ if isSlackDown => Future.failed(new Exception("slack_is_down"))
    case _ if isSlackThrowingAFit => Future.failed(SlackAPIFailure.TokenRevoked)
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
  def getChannelId(token: SlackAccessToken, channelName: SlackChannelName): Future[Option[SlackChannelId]] = Future.successful(None)
  def getTeamInfo(token: SlackAccessToken): Future[SlackTeamInfo] = ???
  def getChannels(token: SlackAccessToken, excludeArchived: Boolean): Future[Seq[SlackChannelInfo]] = ???
  def getChannelInfo(token: SlackAccessToken, channelId: SlackChannelId): Future[SlackChannelInfo] = ???
  def getUserInfo(token: SlackAccessToken, userId: SlackUserId): Future[SlackUserInfo] = ???
}
