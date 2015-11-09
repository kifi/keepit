package com.keepit.slack

import scala.concurrent.Future
import scala.util.Try

class FakeSlackClientImpl extends SlackClient {
  def sendToSlack(url: String, msg: SlackMessage): Future[Try[Unit]] = ???
}
