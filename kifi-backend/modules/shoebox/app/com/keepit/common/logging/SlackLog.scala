package com.keepit.common.logging

import com.keepit.common.util.{ Debouncing, DescriptionElements }
import com.keepit.macros.Location
import com.keepit.slack.models.{ SlackAttachment, SlackMessageRequest }
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }

import scala.concurrent.duration._

class SlackLog(loggingDestination: InhouseSlackChannel)(implicit inhouseSlackClient: InhouseSlackClient) {
  private val debouncer = new Debouncing.Buffer[SlackAttachment]

  def info(elements: DescriptionElements*)(implicit sourceCodeLocation: Location): Unit = sendLog(sourceCodeLocation, elements, "good")
  def warn(elements: DescriptionElements*)(implicit sourceCodeLocation: Location): Unit = sendLog(sourceCodeLocation, elements, "warning")
  def error(elements: DescriptionElements*)(implicit sourceCodeLocation: Location): Unit = sendLog(sourceCodeLocation, elements, "danger")

  private def sendLog(fromLine: Location, text: DescriptionElements, color: String): Unit = {
    debouncer.debounce(fromLine.location, 5 seconds)(
      item = SlackAttachment(color = Some(color), text = Some(DescriptionElements.formatForSlack(text)))
    ) { attachments =>
        val msg = SlackMessageRequest.inhouse(
          DescriptionElements.fromLocation(fromLine),
          attachments = attachments.reverse.take(20)
        )
        inhouseSlackClient.sendToSlack(loggingDestination, msg)
      }
  }
}
