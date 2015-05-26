package com.keepit.eliza.mail

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.{ EmailAddress, ElectronicMail, SystemEmailAddress }
import com.keepit.common.store.ImageSize
import com.keepit.eliza.commanders.{ ElizaEmailUriSummaryImageSizes, ElizaEmailCommander }
import com.keepit.eliza.model._
import com.keepit.eliza.model.ExtendedThreadItem
import com.keepit.eliza.model.UserThread
import com.keepit.model.{ NotificationCategory, UserStates, URISummary, User }
import com.keepit.rover.model.RoverUriSummary
import com.keepit.shoebox.ShoeboxServiceClient
import org.joda.time.Minutes
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.time._

class ElizaUserEmailNotifierActor @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    clock: Clock,
    userThreadRepo: UserThreadRepo,
    threadRepo: MessageThreadRepo,
    shoebox: ShoeboxServiceClient,
    elizaEmailCommander: ElizaEmailCommander) extends ElizaEmailNotifierActor[UserThread](airbrake) {

  import ElizaEmailNotifierActor._

  /**
   * Fetches user threads that need to receive an email update
   */
  protected def getParticipantThreadsToProcess(): Seq[UserThread] = {
    val now = clock.now
    val lastNotifiedBefore = now.minus(MIN_TIME_BETWEEN_NOTIFICATIONS.toMillis)
    val unseenUserThreads = db.readOnlyReplica { implicit session =>
      userThreadRepo.getUserThreadsForEmailing(lastNotifiedBefore)
    }
    val notificationUpdatedAts = unseenUserThreads.map { t => t.id.get -> t.notificationUpdatedAt } toMap;
    log.info(s"[now:$now] [lastNotifiedBefore:$lastNotifiedBefore] found ${unseenUserThreads.size} unseenUserThreads, notificationUpdatedAt: ${notificationUpdatedAts.mkString(",")}")
    unseenUserThreads
  }

  /**
   * Sends email update to all specified user threads corresponding to the same MessageThread
   */
  protected def emailUnreadMessagesForParticipantThreadBatch(batch: ParticipantThreadBatch[UserThread]): Future[Unit] = {
    val userThreads = batch.participantThreads
    val threadId = batch.threadId
    val thread = db.readOnlyReplica { implicit session => threadRepo.get(threadId) }
    val allUserIds = thread.participants.map(_.allUsers).getOrElse(Set()).toSeq
    val allUsersFuture: Future[Map[Id[User], User]] = new SafeFuture(
      shoebox.getUsers(allUserIds).map(s => s.map(u => u.id.get -> u).toMap)
    )
    val allUserImageUrlsFuture: Future[Map[Id[User], String]] = new SafeFuture(FutureHelpers.map(allUserIds.map(u => u -> shoebox.getUserImageUrl(u, 73)).toMap))
    val uriSummaryFuture = elizaEmailCommander.getUriSummary(thread)
    val threadDataFuture = for {
      allUsers <- allUsersFuture
      allUserImageUrls <- allUserImageUrlsFuture
      uriSummary <- uriSummaryFuture
    } yield (allUsers, allUserImageUrls, uriSummary)
    threadDataFuture.flatMap { data =>
      val (allUsers, allUserImageUrls, uriSummary) = data
      // Futures below will be executed concurrently
      val notificationFutures = userThreads.map { userThread =>
        emailUnreadMessagesForUserThread(userThread, thread, allUsers, allUserImageUrls, uriSummary, ElizaEmailUriSummaryImageSizes.smallImageSize).recover {
          case _ => ()
        }
      }
      Future.sequence(notificationFutures).map(_ => ())
    }
  }

  private def emailUnreadMessagesForUserThread(
    userThread: UserThread,
    thread: MessageThread,
    allUsers: Map[Id[User], User],
    allUserImageUrls: Map[Id[User], String],
    uriSummary: Option[RoverUriSummary],
    idealImageSize: ImageSize): Future[Unit] = {
    log.info(s"processing user thread $userThread")
    val now = clock.now
    airbrake.verify(userThread.replyable,
      s"${userThread.summary} not replyable")
    airbrake.verify(userThread.unread,
      s"${userThread.summary} not unread")
    airbrake.verify(!userThread.notificationEmailed,
      s"${userThread.summary} notification already emailed")
    airbrake.verify(userThread.notificationUpdatedAt.isAfter(now.minusHours(5)),
      s"Late send (${Minutes.minutesBetween(now, userThread.notificationUpdatedAt)} min) of user thread ${userThread.summary} notificationUpdatedAt ${userThread.notificationUpdatedAt} ")
    airbrake.verify(userThread.notificationUpdatedAt.isBefore(now),
      s"${userThread.summary} notificationUpdatedAt ${userThread.notificationUpdatedAt} in the future (${Minutes.minutesBetween(userThread.notificationUpdatedAt, now)} min)")

    val extendedThreadItems: Seq[ExtendedThreadItem] = elizaEmailCommander.getExtendedThreadItems(thread, allUsers, allUserImageUrls, userThread.lastSeen, None)

    val result = if (extendedThreadItems.isEmpty) Future.successful(()) else {
      log.info(s"preparing to send email for thread ${thread.id}, user thread ${thread.id} of user ${userThread.user} " +
        s"with notificationUpdatedAt=${userThread.notificationUpdatedAt} and notificationLastSeen=${userThread.notificationLastSeen} " +
        s"with ${extendedThreadItems.size} items and unread=${userThread.unread} and notificationEmailed=${userThread.notificationEmailed}")

      val recipientUserId = userThread.user
      val deepUrlFuture: Future[String] = shoebox.getDeepUrl(thread.deepLocator, recipientUserId)

      deepUrlFuture flatMap { deepUrl =>
        //if user is not active, skip it!
        val recipient = allUsers(recipientUserId)
        val shouldBeEmailed = recipient.state == UserStates.ACTIVE && recipient.primaryEmail.isDefined

        if (!shouldBeEmailed) {
          log.warn(s"user $recipient is not active, not sending emails")
          Future.successful(())
        } else {
          val destinationEmail = recipient.primaryEmail.get
          val futureEmail = shoebox.getUnsubscribeUrlForEmail(destinationEmail).map {
            case unsubUrl =>
              val threadEmailInfo: ThreadEmailInfo =
                elizaEmailCommander.getThreadEmailInfo(thread, uriSummary, idealImageSize, false, allUsers, allUserImageUrls, None,
                  Some(unsubUrl), None).copy(pageUrl = deepUrl, isDeepLink = true)
              val magicAddress = SystemEmailAddress.discussion(userThread.accessToken.token)
              ElectronicMail(
                from = magicAddress,
                fromName = Some("Kifi Notifications"),
                to = Seq(destinationEmail),
                subject = s"""New messages on "${threadEmailInfo.pageTitle}"""",
                htmlBody = views.html.discussionEmail(threadEmailInfo, extendedThreadItems, true, false, true).body,
                category = NotificationCategory.User.MESSAGE
              )
          }

          futureEmail.flatMap { email =>
            shoebox.sendMail(email) map {
              case true =>
              case false => throw new Exception("Shoebox was unable to parse and send the email.")
            }
          }
        }
      }
    }
    log.info(s"processed user thread $userThread")
    // todo(martin) replace with onSuccess when we have better error handling
    result.onComplete { _ =>
      db.readWrite { implicit session => userThreadRepo.setNotificationEmailed(userThread.id.get, userThread.lastMsgFromOther) }
    }
    result
  }
}
