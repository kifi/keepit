package com.keepit.slack

import com.keepit.slack.models._
import play.api.libs.json.JsObject

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.Try

class FakeSlackClientImpl extends SlackClient {
  val messagesByWebhook: mutable.Map[String, List[SlackMessage]] = mutable.Map.empty.withDefaultValue(List.empty)

  def identifyUser(token: SlackAccessToken): Future[SlackIdentifyResponse] = ???
  def processAuthorizationResponse(code: SlackAuthorizationCode): Future[SlackAuthorizationResponse] = ???
  def sendToSlack(url: String, msg: SlackMessage): Future[Unit] = {
    messagesByWebhook.put(url, msg :: messagesByWebhook(url))
    Future.successful(())
  }
  def searchMessages(token: SlackAccessToken, query: SlackSearchQuery, optional: SlackSearchParams*): Future[SlackSearchResponse] = ???
}
