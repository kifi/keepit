package com.keepit.eliza.mail

import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{SafeFuture, FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulerPlugin, SchedulingProperties}
import com.keepit.common.strings._
import com.keepit.common.time._
import com.keepit.eliza.model._
import com.keepit.eliza.util.MessageFormatter
import com.keepit.inject.AppScoped
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient

import com.google.inject.{Inject, ImplementedBy}

import scala.concurrent.duration._

import akka.util.Timeout
import org.joda.time.DateTime
import com.keepit.common.mail._
import scala.concurrent.Future
import com.keepit.common.mail.GenericEmailAddress
import com.keepit.eliza.commanders.ElizaEmailCommander
import com.keepit.common.concurrent.FutureHelpers
import scala.Some
import com.keepit.eliza.model.ThreadEmailInfo
import com.keepit.eliza.model.ExtendedThreadItem
import com.keepit.eliza.model.UserThread
import com.keepit.model.DeepLocator
import play.api.libs.concurrent.Execution.Implicits.defaultContext

case object SendEmails

class ElizaEmailNotifierActor @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    clock: Clock,
    userThreadRepo: UserThreadRepo,
    messageRepo: MessageRepo,
    threadRepo: MessageThreadRepo,
    shoebox: ShoeboxServiceClient,
    elizaEmailCommander: ElizaEmailCommander,
    nonUserThreadRepo: NonUserThreadRepo
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
        sendEmail(userThread)
      }
    case m => throw new UnsupportedActorMessage(m)
  }

  private def sendEmail(userThread: UserThread): Unit = {
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
      messageRepo.get(thread.id.get, 0).filter(!_.from.isSystem)
    }}.map { message =>
      ThreadItem(message.from.asUser, message.from.asNonUser.map(_.toString), MessageFormatter.toText(message.messageText))
    } reverse

    if (threadItems.nonEmpty) {
      log.info(s"preparing to send email for thread ${thread.id}, user thread ${thread.id} of user ${userThread.user} " +
        s"with notificationUpdatedAt=${userThread.notificationUpdatedAt} and notificationLastSeen=${userThread.notificationLastSeen} " +
        s"with ${threadItems.size} items and unread=${userThread.unread} and notificationEmailed=${userThread.notificationEmailed}")
      sendUnreadMessages(userThread, thread, userThread.lastSeen, threadItems, otherParticipants.toSeq, userThread.user, thread.deepLocator)
    }
    log.info(s"processed user thread $userThread")
  }


  def sendUnreadMessages(
    userThread: UserThread,
    thread: MessageThread,
    lastSeen: Option[DateTime],
    threadItems: Seq[ThreadItem],
    otherParticipantIds: Seq[Id[User]],
    recipientUserId: Id[User],
    deepLocator: DeepLocator
  ): Unit = {
    val allUserIds: Seq[Id[User]] = recipientUserId +: otherParticipantIds
    val allUsersFuture : Future[Map[Id[User], User]] = new SafeFuture(
      shoebox.getUsers(allUserIds).map( s => s.map(u => u.id.get -> u).toMap)
    )
    val allUserImageUrlsFuture: Future[Map[Id[User], String]] = new SafeFuture(FutureHelpers.map(allUserIds.map(u => u -> shoebox.getUserImageUrl(u, 73)).toMap))
    val uriSummaryFuture = elizaEmailCommander.getSummarySmall(thread)
    val deepUrlFuture: Future[String] = shoebox.getDeepUrl(thread.deepLocator, recipientUserId)

    for {
      allUsers <- allUsersFuture
      allUserImageUrls <- allUserImageUrlsFuture
      deepUrl <- deepUrlFuture
      uriSummary <- uriSummaryFuture
    } yield {
      //if user is not active, skip it!
      val recipient = allUsers(recipientUserId)
      if (recipient.state == UserStates.ACTIVE || recipient.primaryEmailId.isEmpty) {
        val otherParticipants = allUsers.filter(_._1 != recipientUserId).values.toSeq

        for {
          destinationEmail <- shoebox.getEmailAddressById(recipient.primaryEmailId.get)
          unsubUrl <- shoebox.getUnsubscribeUrlForEmail(destinationEmail)
        } yield {
          val threadEmailInfo: ThreadEmailInfo = elizaEmailCommander.getThreadEmailInfo(thread, uriSummary, allUsers, allUserImageUrls, Some(unsubUrl)).copy(pageUrl = deepUrl)
          val b: Seq[ExtendedThreadItem] = elizaEmailCommander.getExtendedThreadItems(thread, allUsers, allUserImageUrls, lastSeen, None)

          val authorFirst = otherParticipants.map(_.firstName).sorted.mkString(", ")
          val magicAddress = EmailAddresses.discussion(userThread.accessToken.token)
          val email = ElectronicMail(
            from = magicAddress,
            fromName = Some("Kifi Notifications"),
            to = Seq(GenericEmailAddress(destinationEmail)),
            subject = s"""New messages on "${threadEmailInfo.pageTitle}" with $authorFirst""",
            htmlBody = views.html.userDigestEmail(threadEmailInfo, b).body,
            category = NotificationCategory.User.MESSAGE
          )
          shoebox.sendMail(email)
          db.readWrite{ implicit session =>
            userThreadRepo.setNotificationEmailed(userThread.id.get, userThread.lastMsgFromOther)
          }
        }
      } else {
        log.warn(s"user $recipient is not active, not sending emails")
      }
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
