package com.keepit.common.mail

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.logging.Logging
import com.keepit.shoebox.ShoeboxServiceClient
import scala.collection.mutable.{Seq => MSeq}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable.Queue
import akka.actor.Actor
import scala.util.{Success, Failure}
import com.keepit.common.actor.ActorFactory
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import scala.concurrent.duration._
import akka.util.Timeout

trait LocalPostOffice {
  def sendMail(mail: ElectronicMail)(implicit session: RWSession): ElectronicMail
}

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

  def receive = {
    case SendEmail(mail: ElectronicMail) =>
      shoeboxClient.sendMail(mail) onComplete {
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
trait RemotePostOfficePlugin extends SchedulingPlugin {
  def sendMail(mail: ElectronicMail): Unit
}

class RemotePostOfficePluginImpl @Inject() (
    actorFactory: ActorFactory[RemotePostOfficeActor],
    val schedulingProperties: SchedulingProperties)
  extends RemotePostOfficePlugin with Logging {
  implicit val actorTimeout = Timeout(5 seconds)
  private lazy val actor = actorFactory.get()

  override def enabled: Boolean = true
  override def onStart() {
     scheduleTask(actorFactory.system, 30 seconds, 3 minutes, actor, SendQueuedEmails)
  }
  def sendMail(mail: ElectronicMail) = actor ! SendEmail(mail)
}

class RemotePostOfficeImpl @Inject() (
  remotePostOfficePlugin: RemotePostOfficePlugin)
  extends RemotePostOffice with Logging {

  def queueMail(mail: ElectronicMail): ElectronicMail = {
    remotePostOfficePlugin.sendMail(mail)
    mail
  }
}


class ShoeboxPostOfficeImpl @Inject() (mailRepo: ElectronicMailRepo)
  extends LocalPostOffice with Logging {

  def sendMail(mail: ElectronicMail)(implicit session: RWSession): ElectronicMail = {
    val prepared =
      if (mail.htmlBody.value.size > PostOffice.BODY_MAX_SIZE ||
        mail.textBody.isDefined && mail.textBody.get.value.size > PostOffice.BODY_MAX_SIZE) {
        log.warn(s"PostOffice attempted to send an email (${mail.externalId}) longer than ${PostOffice.BODY_MAX_SIZE} bytes. Too big!")
        mailRepo.save(mail.copy(
          htmlBody = mail.htmlBody.value.take(PostOffice.BODY_MAX_SIZE - 20) + "<br>\n<br>\n(snip)").prepareToSend())
      } else {
        mailRepo.save(mail.prepareToSend())
      }
    prepared
  }
}

