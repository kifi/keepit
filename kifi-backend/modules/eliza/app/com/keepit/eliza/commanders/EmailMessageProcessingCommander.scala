package com.keepit.eliza.commanders

import com.google.inject.Inject
import com.keepit.eliza.model._
import com.keepit.common.logging.Logging
import com.kifi.franz.SQSQueue
import com.keepit.eliza.mail.MailNotificationReply
import com.keepit.common.crypto.{PublicIdConfiguration, ModelWithPublicId}
import com.keepit.common.db.Id
import scala.Some
import com.keepit.eliza.model.NonUserThread
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.util.Success
import com.keepit.common.db.slick.Database
import com.keepit.heimdal.HeimdalContextBuilderFactory

class EmailMessageProcessingCommander @Inject() (
  mailNotificationReplyQueue: SQSQueue[MailNotificationReply],
  nonUserThreadRepo: NonUserThreadRepo,
  messagingCommander: MessagingCommander,
  airbrake: AirbrakeNotifier,
  db: Database,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  implicit val config: PublicIdConfiguration) extends Logging {

  def readIncomingMessages() = {
    import NonUserThread._
    while (true) {
      mailNotificationReplyQueue.next.map(_.map { sqsMessage =>
        val message = sqsMessage.body
        ModelWithPublicId.decode[NonUserThread](message.publicId) match {
          case Success(id: Id[NonUserThread]) => {
            message.content match {
              case Some(content: String) => {
                val contextBuilder = heimdalContextBuilder()
                contextBuilder += ("source", "email")
                messagingCommander.sendMessageWithNonUserThread(id, content, None)(contextBuilder.build)
              }
              case None => airbrake.notify(s"Could not extract contents of email: publicId = ${id} and timestamp = ${message.timestamp}")
            }
          }
          case _ => log.info(s"Email with invalid public id ${message.publicId}")
        }
      })
    }
  }
}
