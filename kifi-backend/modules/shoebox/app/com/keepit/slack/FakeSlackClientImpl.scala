package com.keepit.slack

import java.util.concurrent.atomic.AtomicLong

import com.keepit.slack.models._

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.Try

class FakeSlackClientImpl extends SlackClient {
  val pushedMessagesByWebhook: mutable.Map[String, List[SlackMessageRequest]] = mutable.Map.empty.withDefaultValue(List.empty)
  val channelLog: mutable.Map[(SlackTeamId, SlackChannelName), List[SlackMessage]] = mutable.Map.empty.withDefaultValue(List.empty)
  val membershipToken: mutable.Map[SlackAccessToken, SlackTeamMembership] = mutable.Map.empty
  val teamByToken: mutable.Map[SlackAccessToken, SlackTeamInfo] = mutable.Map.empty
  var isSlackDown = false
  var isSlackThrowingAFit = false
  private val inc = new AtomicLong(1442527939)

  val inChannelQuery = """in:([^ ]*)""".r

  def identifyUser(token: SlackAccessToken): Future[SlackIdentifyResponse] = ???
  def processAuthorizationResponse(code: SlackAuthorizationCode): Future[SlackAuthorizationResponse] = ???
  def sendToSlack(url: String, msg: SlackMessageRequest): Future[Unit] = () match {
    case _ if isSlackDown => Future.failed(new Exception("slack_is_down"))
    case _ if isSlackThrowingAFit => Future.failed(SlackAPIFailure.WebhookRevoked)
    case _ =>
      pushedMessagesByWebhook.put(url, msg :: pushedMessagesByWebhook(url))
      Future.successful(())
  }

  def sayInChannel(stm: SlackTeamMembership, ch: SlackChannel)(str: String): Unit = {
    val key = (stm.slackTeamId, ch.name)
    val msgId = inc.incrementAndGet()
    val msg = SlackMessage(
      messageType = SlackMessageType("message"),
      userId = stm.slackUserId,
      username = stm.slackUsername,
      timestamp = SlackMessageTimestamp(s"$msgId.00000"),
      channel = ch,
      text = str,
      attachments = Seq.empty,
      permalink = s"https://slack.com/$msgId"
    )
    stm.token.foreach { tk => membershipToken.put(tk, stm) }
    channelLog.put(key, msg :: channelLog(key))
  }
  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse] = () match {
    case _ if isSlackDown => Future.failed(new Exception("slack_is_down"))
    case _ if isSlackThrowingAFit => Future.failed(SlackAPIFailure.TokenRevoked)
    case _ =>
      val stmOpt = membershipToken.get(token)
      val bestGuessChannel = inChannelQuery.findAllMatchIn(request.query.query).toList.headOption.map(_.subgroups.head)
      val matches = (for {
        stm <- membershipToken.get(token)
        chName <- bestGuessChannel
        msgs <- channelLog.get((stm.slackTeamId, SlackChannelName(chName)))
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

  def addReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackMessageTimestamp): Future[Unit] = Future.successful(())
  def getChannelId(token: SlackAccessToken, channelName: SlackChannelName): Future[Option[SlackChannelId]] = Future.successful(None)
  def getTeamInfo(token: SlackAccessToken): Future[SlackTeamInfo] = ???
}
