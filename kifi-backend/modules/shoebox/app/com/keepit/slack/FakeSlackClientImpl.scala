package com.keepit.slack

import com.keepit.slack.models._
import play.api.libs.json.JsObject

import scala.concurrent.Future
import scala.util.Try

class FakeSlackClientImpl extends SlackClient {
  def identifyUser(token: SlackAccessToken): Future[SlackIdentifyResponse] = ???
  def processAuthorizationResponse(code: SlackAuthorizationCode): Future[SlackAuthorizationResponse] = ???
  def sendToSlack(url: String, msg: SlackMessage): Future[Unit] = Future.successful(())
  def searchMessages(token: SlackAccessToken, query: SlackSearchQuery, optional: SlackSearchParams*): Future[SlackSearchResponse] = ???
}
