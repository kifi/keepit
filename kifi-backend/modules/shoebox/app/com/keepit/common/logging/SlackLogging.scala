package com.keepit.common.logging

import com.keepit.common.util.DescriptionElements
import com.keepit.slack.models.SlackMessageRequest
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }

trait SlackLogging {
  val inhouseSlackClient: InhouseSlackClient
  val loggingDestination: InhouseSlackChannel

  def log(elements: DescriptionElements): Unit = inhouseSlackClient.sendToSlack(loggingDestination, SlackMessageRequest.inhouse(elements))
}
