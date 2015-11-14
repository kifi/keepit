package com.keepit.slack

import com.keepit.slack.models._
import scala.concurrent.Future

class FakeSlackClientImpl extends SlackClient {
  def identifyUser(token: SlackAccessToken): Future[SlackIdentifyResponse] = ???
  def processAuthorizationResponse(code: SlackAuthorizationCode): Future[SlackAuthorizationResponse] = ???
  def sendToSlack(url: String, msg: OutgoingSlackMessage): Future[Unit] = Future.successful(())
  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse] = ???
}
