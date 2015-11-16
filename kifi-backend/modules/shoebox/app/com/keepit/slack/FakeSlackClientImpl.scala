package com.keepit.slack

import com.keepit.slack.models._

import scala.collection.mutable
import scala.concurrent.Future

class FakeSlackClientImpl extends SlackClient {
  val messagesByWebhook: mutable.Map[String, List[SlackMessageRequest]] = mutable.Map.empty.withDefaultValue(List.empty)

  def identifyUser(token: SlackAccessToken): Future[SlackIdentifyResponse] = ???
  def processAuthorizationResponse(code: SlackAuthorizationCode): Future[SlackAuthorizationResponse] = ???
  def sendToSlack(url: String, msg: SlackMessageRequest): Future[Unit] = {
    messagesByWebhook.put(url, msg :: messagesByWebhook(url))
    Future.successful(())
  }
  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse] = ???
}
