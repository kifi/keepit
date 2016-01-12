package com.keepit.common.logging

import com.keepit.common.util.DescriptionElements
import com.keepit.macros.Location
import com.keepit.slack.models.{ SlackAttachment, SlackMessageRequest }
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }

trait SlackLogging {
  val inhouseSlackClient: InhouseSlackClient
  val loggingDestination: InhouseSlackChannel
  object log {
    def info(elements: DescriptionElements*)(implicit sourceCodeLocation: Location): Unit = sendLog(sourceCodeLocation, elements, "good")
    def warn(elements: DescriptionElements*)(implicit sourceCodeLocation: Location): Unit = sendLog(sourceCodeLocation, elements, "warning")
    def error(elements: DescriptionElements*)(implicit sourceCodeLocation: Location): Unit = sendLog(sourceCodeLocation, elements, "danger")

    private def sendLog(fromLine: Location, text: DescriptionElements, color: String): Unit = {
      val msg = SlackMessageRequest.inhouse(
        DescriptionElements.fromLocation(fromLine),
        attachments = Seq(SlackAttachment(color = Some(color), text = Some(DescriptionElements.formatForSlack(text))))
      )
      inhouseSlackClient.sendToSlack(loggingDestination, msg)
    }
  }
}
