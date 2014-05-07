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
import scala.util.{Success, Failure}
import com.keepit.common.db.slick.Database
import com.keepit.heimdal.HeimdalContextBuilderFactory
import scala.concurrent.duration._

class EmailMessageProcessingCommander @Inject() (
  mailNotificationReplyQueue: SQSQueue[MailNotificationReply],
  nonUserThreadRepo: NonUserThreadRepo,
  messagingCommander: MessagingCommander,
  airbrake: AirbrakeNotifier,
  db: Database,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  implicit val config: PublicIdConfiguration) extends Logging {

  def readIncomingMessages(): Unit = {
    import NonUserThread._
    mailNotificationReplyQueue.nextWithLock(1 minute).onComplete{
      case Success(result) => {
        try {
          result.map { sqsMessage =>
            val message = sqsMessage.body
            val nutOpt = messagingCommander.getNonUserThreadOptByAccessToken(ThreadAccessToken(message.token))
            nutOpt.map { nut =>
              message.content match {
                case Some(content: String) => {
                  val contextBuilder = heimdalContextBuilder()
                  contextBuilder += ("source", "email")
                  messagingCommander.sendMessageWithNonUserThread(nut, content, Some(MessageSource.EMAIL), None)(contextBuilder.build)
                }
                case None => airbrake.notify(s"Could not extract contents of email: token = ${message.token} and timestamp = ${message.timestamp}")
              }
            } getOrElse {
              airbrake.notify(s"Invalid Access Token ${message.token}")
            }
            sqsMessage.consume()
          }
        } catch {
          case e:Throwable => log.warn(s"Failed to read messages: ${e.getMessage()}")
        }
        readIncomingMessages()
      }
      case Failure(t) => {
        log.info("RConn: Queue call failed")
        airbrake.notify("Failed reading incoming messages from queue", t)
      }
    }
  }
}
