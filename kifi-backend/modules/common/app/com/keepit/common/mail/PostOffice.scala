package com.keepit.common.mail

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.logging.Logging
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable.Queue
import akka.actor.Actor
import scala.util.{Success, Failure}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import scala.concurrent.duration._
import akka.util.Timeout
import play.api.Plugin



@ImplementedBy(classOf[RemotePostOfficeImpl])
trait RemotePostOffice {
  def queueMail(mail: ElectronicMail): ElectronicMail
}

object PostOffice {
  object Categories {
    val HEALTHCHECK = ElectronicMailCategory("HEALTHCHECK")
    val ASANA_HEALTHCHECK = ElectronicMailCategory("ASANA_HEALTHCHECK")
    val COMMENT = ElectronicMailCategory("COMMENT")
    val MESSAGE = ElectronicMailCategory("MESSAGE")
    val ADMIN = ElectronicMailCategory("ADMIN")
    val EMAIL_KEEP = ElectronicMailCategory("EMAIL_KEEP")
    val INVITATION = ElectronicMailCategory("INVITATION")
  }

  val BODY_MAX_SIZE = 1048576
}

sealed trait PostOfficeMessage
case class SendEmail(mail: ElectronicMail) extends PostOfficeMessage
case class QueueEmail(mail: ElectronicMail) extends PostOfficeMessage
case object SendQueuedEmails extends PostOfficeMessage

class RemotePostOfficeActor @Inject() (shoeboxClient: ShoeboxServiceClient)
  extends Actor { // we cannot use an AlertingActor, because this generated Healthcheck errors on failure

  val mailQueue = Queue[ElectronicMail]()
  val Max8M = 8 * 1024 * 1024

  def receive = {
    case SendEmail(mail: ElectronicMail) =>
      shoeboxClient.sendMail(mail.copy(htmlBody = mail.htmlBody.value.take(Max8M), textBody = mail.textBody.map(_.value.take(Max8M)))) onComplete {
        case Success(result)  => if(!result) self ! QueueEmail(mail)
        case Failure(failure) => self ! QueueEmail(mail)
      }
    case QueueEmail(mail) =>
      mailQueue.enqueue(mail)
    case SendQueuedEmails =>
      mailQueue.foreach( mail => self ! SendEmail(mail))
  }
}

@ImplementedBy(classOf[RemotePostOfficePluginImpl])
trait RemotePostOfficePlugin extends Plugin {
  def sendMail(mail: ElectronicMail): Unit
}

class RemotePostOfficePluginImpl @Inject() (
    actor: ActorInstance[RemotePostOfficeActor])
  extends RemotePostOfficePlugin with SchedulingPlugin {

  val schedulingProperties = SchedulingProperties.AlwaysEnabled

  implicit val actorTimeout = Timeout(5 seconds)
  override def enabled: Boolean = true
  override def onStart() {
     scheduleTask(actor.system, 30 seconds, 3 minutes, actor.ref, SendQueuedEmails)
  }
  def sendMail(mail: ElectronicMail) = actor.ref ! SendEmail(mail)
}

class RemotePostOfficeImpl @Inject() (
  remotePostOfficePlugin: RemotePostOfficePlugin)
  extends RemotePostOffice with Logging {

  def queueMail(mail: ElectronicMail): ElectronicMail = {
    remotePostOfficePlugin.sendMail(mail)
    mail
  }
}
