package com.keepit.eliza.mail

import scala.concurrent.duration._

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulerPlugin, SchedulingProperties}

import javax.mail._
import play.api.Plugin
import com.keepit.common.db.Id
import com.keepit.common.mail.GenericMailParser
import com.keepit.common.crypto.ModelWithPublicId
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.eliza.model.NonUserThread
import scala.Some
import com.kifi.franz.SQSQueue
import play.api.libs.concurrent.Execution.Implicits.defaultContext

private case object FetchNewDiscussionReplies

case class MailDiscussionServerSettings(
  identifier: String,
  domain: String,
  password: String,
  server: String,
  protocol: String
) {
  val username: String = identifier + "@" + domain
}

case class MailNotificationReply(timestamp:DateTime, content:Option[String], publicId:String)

object MailNotificationReply {
  implicit val format = (
    (__ \ 'timestamp).format[DateTime] and
    (__ \ 'content).formatNullable[String] and
    (__ \ 'publicId).format[String]
  )(MailNotificationReply.apply _, unlift(MailNotificationReply.unapply))
}

class MailDiscussionReceiverActor @Inject() (
  airbrake: AirbrakeNotifier,
  settings: MailDiscussionServerSettings,
  messageParser: MailDiscussionMessageParser,
  mailNotificationReplyQueue: SQSQueue[MailNotificationReply]
  ) extends FortyTwoActor(airbrake) with Logging {

  private lazy val mailSession: Session = {
    val props = System.getProperties()
    props.setProperty("mail.store.protocol", settings.protocol)
    Session.getInstance(props, null)
  }

  def receive = {
    case FetchNewDiscussionReplies =>
      log.info("Checking for new replies")
      val store = mailSession.getStore
      try {
        store.connect(settings.server, settings.username, settings.password)
        val inbox = store.getFolder("Inbox")
        inbox.open(Folder.READ_WRITE)
        val unrecognized = store.getFolder("Unrecognized")
        val messages = inbox.getMessages()
        for (message <- messages) {
          messageParser.getInfo(message) match {
            case Some(mailNotificationReply) => {
              log.info("Received valid message")
              mailNotificationReplyQueue.send(mailNotificationReply)
            }
            case None => {
              log.info("Received invalid message")
              inbox.copyMessages(Array(message), unrecognized)
            }
          }
          message.setFlags(new Flags(Flags.Flag.DELETED), true)
        }
        inbox.close(true)
      } finally {
        store.close()
      }
    case m => throw new UnsupportedActorMessage(m)
  }
}

class MailDiscussionMessageParser @Inject() (
  db: Database,
  settings: MailDiscussionServerSettings,
  implicit val publicIdConfiguration: PublicIdConfiguration
  ) extends GenericMailParser {

  private val DiscussionEmail = raw"""^${settings.identifier}\+(\w+)@[\w\.]+$$""".r

  private def getPublicId(message: Message): Option[String] = {
    message.getAllRecipients.map(getAddr).map {
      DiscussionEmail.findFirstMatchIn(_).map(_.group(1))
    }.flatten.headOption
  }

  private def getTimestamp(message: Message): DateTime = {
    new DateTime(message.getReceivedDate())
  }

  private def getContent(message: Message): Option[String] = {
    getText(message)
  }

  def getInfo(message: Message): Option[MailNotificationReply] = {
    getPublicId(message).flatMap(getInfoFromIdentifier)
    getPublicId(message).map(MailNotificationReply(getTimestamp(message), getContent(message), _))
  }

  private def getInfoFromIdentifier(identifier: String): Option[Id[NonUserThread]] = {
    ModelWithPublicId.decode[NonUserThread](identifier).toOption
  }
}

@ImplementedBy(classOf[MailMessageReceiverPluginImpl])
trait MailMessageReceiverPlugin extends Plugin {
  def fetchNewDiscussionMessages()
}

class MailMessageReceiverPluginImpl @Inject()(
  actor: ActorInstance[MailDiscussionReceiverActor],
  val scheduling: SchedulingProperties //only on leader
) extends MailMessageReceiverPlugin with SchedulerPlugin {

  override def enabled: Boolean = true

  def fetchNewDiscussionMessages() {
    actor.ref ! FetchNewDiscussionReplies
  }
  override def onStart() {
    scheduleTaskOnLeader(actor.system, 10 seconds, 15 seconds, actor.ref, FetchNewDiscussionReplies)
  }
}

class FakeMailMessageReceiverPlugin @Inject() extends MailMessageReceiverPlugin with Logging {
  def fetchNewDiscussionMessages() {
    log.info("Fake fetching new discussion messages")
  }
}
