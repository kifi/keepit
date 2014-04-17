package com.keepit.eliza.mail

import com.keepit.eliza.model._
import com.keepit.common.plugin.{SchedulerPlugin, SchedulingProperties}
import com.keepit.common.logging.Logging
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.common.strings._
import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.inject.AppScoped


import com.google.inject.{Inject, ImplementedBy}

import scala.concurrent.duration._
import scala.util.matching.Regex.Match

import akka.util.Timeout

object MessageLookHereRemover {
  private[this] val lookHereRegex = """\[((?:\\\]|[^\]])*)\](\(x-kifi-sel:((?:\\\)|[^)])*)\))""".r

  def apply(text: String): String = {
    lookHereRegex.replaceAllIn(text, (m: Match) => m.group(1))
  }
}

case object SendEmails

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
      val now = clock.now
      val startTime = now.minusMinutes(15)
      val unseenUserThreads = db.readOnly { implicit session =>
        userThreadRepo.getUserThreadsForEmailing(startTime)
      }
      val notificationUpdatedAts = unseenUserThreads.map { t => t.id.get -> t.notificationUpdatedAt } toMap;
      log.info(s"[now:$now] [cut:$startTime] found ${unseenUserThreads.size} unseenUserThreads, notificationUpdatedAt: ${notificationUpdatedAts.mkString(",")}")
      unseenUserThreads.foreach { userThread =>
        sendEmailViaShoebox(userThread)
      }
    case m => throw new UnsupportedActorMessage(m)
  }

  private def sendEmailViaShoebox(userThread: UserThread): Unit = {
    log.info(s"processing user thread $userThread")
    val now = clock.now
    airbrake.verify(userThread.replyable, s"${userThread.summary} not replyable")
    airbrake.verify(userThread.unread, s"${userThread.summary} not unread")
    airbrake.verify(!userThread.notificationEmailed, s"${userThread.summary} notification already emailed")
    airbrake.verify(userThread.notificationUpdatedAt.isAfter(now.minusMinutes(30)), s"${userThread.summary} notificationUpdatedAt ${userThread.notificationUpdatedAt} ")
    airbrake.verify(userThread.notificationUpdatedAt.isBefore(now), s"${userThread.summary} notificationUpdatedAt ${userThread.notificationUpdatedAt} in the future")

    val thread = db.readOnly { implicit session => threadRepo.get(userThread.thread) }

    //everyone exception user who we about to email
    val otherParticipants: Set[Id[User]] = thread.participants.map(_.allUsers).getOrElse(Set()) - userThread.user

    val threadItems: Seq[ThreadItem] =  db.readOnly { implicit session => userThread.lastSeen.map { lastSeen =>
      messageRepo.getAfter(thread.id.get, lastSeen).filter(!_.from.isSystem)
    } getOrElse {
      messageRepo.get(thread.id.get, 0, None).filter(!_.from.isSystem)
    }}.map { message =>
      ThreadItem(message.from.asUser, message.from.asNonUser.map(_.toString), MessageLookHereRemover(message.messageText))
    } reverse

    log.info(s"preparing to send email for thread ${thread.id}, user thread ${thread.id} of user ${userThread.user} " +
      s"with notificationUpdatedAt=${userThread.notificationUpdatedAt} and notificationLastSeen=${userThread.notificationLastSeen} " +
      s"with ${threadItems.size} items and unread=${userThread.unread} and notificationEmailed=${userThread.notificationEmailed}")

    if (threadItems.nonEmpty) {
      val title  = thread.pageTitle.getOrElse(thread.nUrl.get).abbreviate(50)
      shoebox.sendUnreadMessages(threadItems, otherParticipants, userThread.user, title, thread.deepLocator, userThread.notificationUpdatedAt)
    }
    db.readWrite{ implicit session =>
      userThreadRepo.setNotificationEmailed(userThread.id.get, userThread.lastMsgFromOther)
    }
    log.info(s"processed user thread $userThread")
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
