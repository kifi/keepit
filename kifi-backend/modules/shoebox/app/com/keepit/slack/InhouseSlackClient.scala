package com.keepit.slack

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.reflection.Enumerator
import com.keepit.common.time.Clock
import com.keepit.slack.models._
import play.api.Mode._
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class InhouseSlackClient @Inject() (
    slackClient: SlackClient,
    clock: Clock,
    airbrake: AirbrakeNotifier,
    implicit val executionContext: ExecutionContext,
    mode: Mode) {
  def sendToSlack(channel: InhouseSlackChannel, msg: SlackMessageRequest): Future[Unit] = {
    if (mode == Prod) slackClient.pushToWebhook(channel.webhookUrl, msg) else Future.successful(())
  }
}

// The `value` does not have to be correct, it is just for display purposes
sealed abstract class InhouseSlackChannel(val value: String, val webhookUrl: String)
object InhouseSlackChannel extends Enumerator[InhouseSlackChannel] {
  case object BILLING_ALERTS extends InhouseSlackChannel("#billing-alerts", "https://hooks.slack.com/services/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")
  case object SLACK_ALERTS extends InhouseSlackChannel("#slack-alerts", "https://hooks.slack.com/services/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")
  case object ENG_SLACK extends InhouseSlackChannel("#eng-slack", "https://hooks.slack.com/services/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")
  case object ENG_SHOEBOX extends InhouseSlackChannel("#eng-shoebox", "https://hooks.slack.com/services/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")
  case object KIFIBOT_FUNNEL extends InhouseSlackChannel("#kifibot-funnel", "https://hooks.slack.com/services/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")
  case object IP_CLUSTERS extends InhouseSlackChannel("#ip-clusters", "https://hooks.slack.com/services/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")
  case object TEST_RYAN extends InhouseSlackChannel("#test-ryan", "https://hooks.slack.com/services/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")
  case object TEST_CAM extends InhouseSlackChannel("#test-cam", "https://hooks.slack.com/services/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")
  case object TEST_LEO extends InhouseSlackChannel("#test-leo", "https://hooks.slack.com/services/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")
}
