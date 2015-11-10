package com.keepit.slack

import play.api.libs.json.JsObject

import scala.concurrent.Future

class FakeSlackClientImpl extends SlackClient {
  def sendToSlack(url: String, msg: SlackMessage): Future[Unit] = ???
  def generateAuthorizationRequest(scopes: Set[SlackAuthScope], state: JsObject): String = ???
  def processAuthorizationResponse(code: SlackAuthorizationCode, state: String): Future[(SlackAuthorizationResponse, JsObject)] = ???
}
