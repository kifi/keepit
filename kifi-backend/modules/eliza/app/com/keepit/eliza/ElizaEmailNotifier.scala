package com.keepit.eliza

import com.keepit.eliza.model._
import com.keepit.common.plugin.{SchedulerPlugin, SchedulingProperties}
import com.keepit.common.logging.Logging
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.model.{NotificationCategory, UserStates, User}
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.mail.{ElectronicMail,EmailAddresses,PostOffice}
import com.keepit.inject.AppScoped
import play.api.libs.concurrent.Execution.Implicits.defaultContext


import com.google.inject.{Inject, ImplementedBy}

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.matching.Regex.Match

import akka.util.Timeout


object MessageLookHereRemover {
  private[this] val lookHereRegex = """\[((?:\\\]|[^\]])*)\](\(x-kifi-sel:((?:\\\)|[^)])*)\))""".r

  def apply(text: String): String = {
    lookHereRegex.replaceAllIn(text, (m: Match) => m.group(1))
  }
}

case object SendEmails

//case class UserMessageInfo(userName: String, userId: ExternalId[User], text: String)

class ElizaEmailNotifierActor @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    clock: Clock,
    userThreadRepo: UserThreadRepo,
    messageRepo: MessageRepo,
    threadRepo: MessageThreadRepo,
    shoebox: ShoeboxServiceClient
  ) extends FortyTwoActor(airbrake) with Logging {

  def receive = {
    case SendEmails =>
      val unseenUserThreads = db.readOnly { implicit session =>
        userThreadRepo.getUserThreadsForEmailing(clock.now.minusMinutes(15))
      }
      unseenUserThreads.foreach { userThread =>
        createEmailAndSend(userThread)
      }
    case m => throw new UnsupportedActorMessage(m)
  }


  private def createEmailAndSend(userThread: UserThread): Unit = {
    val thread = db.readOnly { implicit session =>  threadRepo.get(userThread.thread) }

    val userParticipantSet : Set[Id[User]] = thread.participants.map(_.allUsers).getOrElse(Set())
    val userIsActiveFuture = shoebox.getUsers(Seq(userThread.user)).map(_.headOption.map(_.state == UserStates.ACTIVE).getOrElse(false))
    val id2BasicUser = Await.result(shoebox.getBasicUsers(userParticipantSet.toSeq), 5 seconds)
    val otherParticipants = (userParticipantSet - userThread.user).map(id2BasicUser(_))

    val unseenMessages =  db.readOnly { implicit session => userThread.lastSeen.map { lastSeen =>
      messageRepo.getAfter(thread.id.get, lastSeen).filter(_.from.isDefined)
    } getOrElse {
      messageRepo.get(thread.id.get, 0, None).filter(_.from.isDefined)
    }}.map { message =>
      val user = id2BasicUser(message.from.get)
      (user.firstName + " " + user.lastName, user.externalId.id, MessageLookHereRemover(message.messageText))
    } reverse

    if (unseenMessages.nonEmpty) {

      val authorFirstLast = otherParticipants.toSeq.map(user => user.firstName + " " + user.lastName).sorted

      val title  = thread.pageTitle.getOrElse(thread.nUrl.get)
      val emailBody = views.html.email.unreadMessages(id2BasicUser(userThread.user), authorFirstLast, unseenMessages, thread.nUrl.get, title).body
      val textBody = views.html.email.unreadMessagesPlain(id2BasicUser(userThread.user), authorFirstLast, unseenMessages, thread.nUrl.get, title).body

      val authorFirst = otherParticipants.toSeq.map(_.firstName).sorted.mkString(", ")
      val formattedTitle = if(title.length > 50) title.take(50) + "..." else title

      val email = ElectronicMail(
        from = EmailAddresses.NOTIFICATIONS, fromName = Some("Kifi Notifications"),
        to = List(),
        subject = s"""New messages on "$formattedTitle" with $authorFirst""",
        htmlBody = emailBody,
        textBody = Some(textBody),
        category = NotificationCategory.User.MESSAGE
      )

      val userIsActive = Await.result(userIsActiveFuture, 5 seconds)
      if (userIsActive) {
        shoebox.sendMailToUser(userThread.user, email)
      }

      db.readWrite{ implicit session => userThreadRepo.setNotificationEmailed(userThread.id.get, userThread.lastMsgFromOther) }
    }
  }
}

@ImplementedBy(classOf[ElizaEmailNotifierPluginImpl])
trait ElizaEmailNotifierPlugin extends SchedulerPlugin {
  def sendEmails(): Unit
}

@AppScoped
class ElizaEmailNotifierPluginImpl @Inject() (
    actor: ActorInstance[ElizaEmailNotifierActor],
    val scheduling: SchedulingProperties)
  extends ElizaEmailNotifierPlugin with Logging {

  implicit val actorTimeout = Timeout(5 second)

  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting ElizaEmailNotifierPluginImpl")
    scheduleTaskOnLeader(actor.system, 30 seconds, 2 minutes, actor.ref, SendEmails)
  }

  override def sendEmails() {
    actor.ref ! SendEmails
  }
}
