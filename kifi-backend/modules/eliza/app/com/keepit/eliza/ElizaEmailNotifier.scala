package com.keepit.eliza

import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.logging.Logging
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.model.{User}
import com.keepit.common.db.{Id}
import com.keepit.shoebox.{ShoeboxServiceClient}
import com.keepit.common.mail.{ElectronicMail,EmailAddresses,PostOffice}

import com.google.inject.{Inject, ImplementedBy}

import scala.concurrent.duration._
import scala.concurrent.Await

import akka.util.Timeout


case object SendEmails

class ElizaEmailNotifierActor @Inject() (
    healthcheck: HealthcheckPlugin,
    db: Database,
    clock: Clock,
    userThreadRepo: UserThreadRepo,
    messageRepo: MessageRepo,
    threadRepo: MessageThreadRepo,
    shoebox: ShoeboxServiceClient
  ) extends FortyTwoActor(healthcheck) with Logging {


  def receive = {
    case SendEmails => {
      val unseenUserThreads = db.readOnly { implicit session => 
        userThreadRepo.getUserThreadsForEmailing(clock.now.minusMinutes(5))
      }
      unseenUserThreads.foreach { userThread =>
        val thread = db.readOnly { implicit session =>  threadRepo.get(userThread.thread) }

        val participantSet : Set[Id[User]] = thread.participants.map(_.all).getOrElse(Set())
        val id2BasicUser = Await.result(shoebox.getBasicUsers(participantSet.toSeq), 5 seconds)
        val otherParticipants = (participantSet - userThread.user).map(id2BasicUser(_))

        val unseenMessages =  db.readOnly { implicit session => userThread.lastSeen.map{ lastSeen =>
          messageRepo.getAfter(thread.id.get, lastSeen)
        } getOrElse {
          messageRepo.get(thread.id.get, 0, None)
        }}.map{ message =>
          val user = id2BasicUser(message.from.get)
          (user.firstName + " " + user.lastName, user.externalId.id, message.messageText)
        }

        if (unseenMessages.nonEmpty) {

          val authorFirstLast = otherParticipants.toSeq.map(user => user.firstName + " " + user.lastName).sorted

          val title  = thread.pageTitle.getOrElse(thread.nUrl.get)
          val emailBody = views.html.email.unreadMessages(id2BasicUser(userThread.user), authorFirstLast, unseenMessages, thread.nUrl.get, title).body
          val textBody = views.html.email.unreadMessagesPlain(id2BasicUser(userThread.user), authorFirstLast, unseenMessages, thread.nUrl.get, title).body

          val authorFirst = otherParticipants.toSeq.map(_.firstName).sorted.mkString(", ")
          val formattedTitle = if(title.length > 50) title.take(50) + "..." else title

          val email = ElectronicMail(
            from = EmailAddresses.NOTIFICATIONS, fromName = Some("KiFi Notifications"),
            to = List(),
            subject = s"""New messages on "${formattedTitle}" with $authorFirst""",
            htmlBody = emailBody,
            textBody = Some(textBody),
            category = PostOffice.Categories.COMMENT
          )

          shoebox.sendMailToUser(userThread.user, email)

          db.readWrite{ implicit session => userThreadRepo.setNotificationEmailed(userThread.id.get, userThread.lastMsgFromOther) }


        }
      }
    }
  }
}
@ImplementedBy(classOf[ElizaEmailNotifierPluginImpl])
trait ElizaEmailNotifierPlugin extends SchedulingPlugin {
  def sendEmails(): Unit
}

class ElizaEmailNotifierPluginImpl @Inject() (
    actor: ActorInstance[ElizaEmailNotifierActor],
    val schedulingProperties: SchedulingProperties) //only on leader
  extends ElizaEmailNotifierPlugin with Logging {

  implicit val actorTimeout = Timeout(5 second)

  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting ElizaEmailNotifierPluginImpl")
    scheduleTask(actor.system, 30 seconds, 2 minutes, actor.ref, SendEmails)
  }

  override def sendEmails() {
    actor.ref ! SendEmails
  }
}
