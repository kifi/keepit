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
import com.keepit.eliza.model.NonUserThread
import com.keepit.common.db.Id
import com.keepit.common.mail.GenericMailParser
import com.keepit.common.crypto.{ModelWithPublicId, PublicIdConfiguration}

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

class MailDiscussionReceiverActor @Inject() (
  airbrake: AirbrakeNotifier,
  settings: MailDiscussionServerSettings,
  messageParser: MailDiscussionMessageParser
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
            case Some(nonUserThreadId) => {
              // TODO(martin) add this message to SQS
              log.info("Received valid message")
            }
            case None => {
              // TODO(martin) log?
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

  private def sendReply(message: javax.mail.Message, htmlBody: String): Unit = {
    // TODO(martin) We may want to send a reply to the sender in some cases ("Congratulations, you sent your first kifi message!")
  }
}

class MailDiscussionMessageParser @Inject() (
  db: Database,
  settings: MailDiscussionServerSettings,
  implicit val publicIdConfiguration: PublicIdConfiguration
  ) extends GenericMailParser {

  private val DiscussionEmail = raw"""^${settings.identifier}\+(\w+)@[\w\.]+$$""".r

  def getInfo(message: Message): Option[Id[NonUserThread]] = {
    message.getAllRecipients.map(getAddr).map {
      DiscussionEmail.findFirstMatchIn(_).map(_.group(1))
    }.flatten.headOption.flatMap(getInfoFromIdentifier)
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
