package com.keepit.slack

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.reflection.Enumerator
import com.keepit.common.time.Clock
import com.keepit.slack.models._

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class InhouseSlackClient @Inject() (
    slackClient: SlackClient,
    clock: Clock,
    airbrake: AirbrakeNotifier,
    implicit val executionContext: ExecutionContext) {
  def sendToSlack(channel: InhouseSlackChannel, msg: SlackMessageRequest): Future[Unit] = {
    slackClient.pushToWebhook(channel.webhookUrl, msg)
  }
}

// The `value` does not have to be correct, it is just for display purposes
sealed abstract class InhouseSlackChannel(val value: String, val webhookUrl: String)
object InhouseSlackChannel extends Enumerator[InhouseSlackChannel] {
  case object BILLING_ALERTS extends InhouseSlackChannel("#billing-alerts", "https://hooks.slack.com/services/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")
  case object SLACK_ALERTS extends InhouseSlackChannel("#slack-alerts", "https://hooks.slack.com/services/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")
  case object IP_CLUSTERS extends InhouseSlackChannel("#ip-clusters", "https://hooks.slack.com/services/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")
  case object TEST_RYAN extends InhouseSlackChannel("#test-ryan", "https://hooks.slack.com/services/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")
}
