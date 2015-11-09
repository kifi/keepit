package com.keepit.slack

import scala.concurrent.Future

class FakeSlackClientImpl extends SlackClient {
  def sendToSlack(url: String, msg: BasicSlackMessage): Future[SlackResponse] = ???
}
