package com.keepit.slack

import play.api.libs.json.JsObject

import scala.concurrent.Future
import scala.util.Try

class FakeSlackClientImpl extends SlackClient {
  def sendToSlack(url: String, msg: SlackMessage): Future[Try[Unit]] = ???
  def generateAuthorizationRequest(scopes: Set[SlackAuthScope], state: JsObject, redirectUri: Option[String] = None): String = ???
  def processAuthorizationResponse(code: SlackAuthorizationCode, state: String): Future[Try[(SlackAuthorizationResponse, JsObject)]] = ???
}
