package com.keepit.eliza.mail

import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{SafeFuture, FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulerPlugin, SchedulingProperties}
import com.keepit.common.time._
import com.keepit.eliza.model._
import com.keepit.inject.AppScoped
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient

import com.google.inject.{Inject, ImplementedBy}

import scala.concurrent.duration._
import org.joda.time.Minutes

import akka.util.Timeout
import com.keepit.common.mail._
import scala.concurrent.Future
import com.keepit.common.mail.GenericEmailAddress
import com.keepit.eliza.commanders.ElizaEmailCommander
import com.keepit.eliza.model.ThreadEmailInfo
import com.keepit.eliza.model.ExtendedThreadItem
import com.keepit.eliza.model.UserThread
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import ElizaEmailNotifierActor._
import com.keepit.social.NonUserKinds
import scala.collection.mutable
import com.keepit.common.concurrent.FutureHelpers

object ElizaEmailNotifierActor {
  case class SendNextUserEmails(num: Int)
  case class SendNextNonUserEmails(num: Int)
  case object EndUserThreadBatchProcessing
  case object EndNonUserThreadBatchProcessing
  val MIN_TIME_BETWEEN_NOTIFICATIONS = 15 minutes
  val RECENT_ACTIVITY_WINDOW = 24 hours
  val MAX_CONCURRENT_USER_THREAD_BATCHES = 1
  val MAX_CONCURRENT_NON_USER_THREAD_BATCHES = 1
}

class ParticipantThreadBatch[T <: ParticipantThread](val participantThreads: Seq[T], val threadId: Id[MessageThread])
object ParticipantThreadBatch {
  def apply[T <: ParticipantThread](participantThreads: Seq[T]) = {
    require(participantThreads.nonEmpty)
    val threadId = participantThreads.head.threadId
    require(participantThreads.forall(_.threadId == threadId)) // All user threads must correspond to the same MessageThread
    new ParticipantThreadBatch(participantThreads, threadId)
  }
}

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

  private val userThreadBatchQueue = new mutable.Queue[ParticipantThreadBatch[UserThread]]()
  private val nonUserThreadBatchQueue = new mutable.Queue[ParticipantThreadBatch[NonUserThread]]()
  private var processingUserTreadBatch = false
  private var processingNonUserThreadBatch = true

  def receive = {
    case SendNextUserEmails(num) =>
      log.info("Attempting to process next user emails")
      if (!processingUserTreadBatch) {
        if (userThreadBatchQueue.isEmpty) {
          val userThreadBatches = getUserThreadsToProcess()
          userThreadBatches.foreach(userThreadBatchQueue.enqueue(_))
        }
        if (userThreadBatchQueue.nonEmpty) {
          val tasks = Future.sequence((1 to num).map { _ =>
            log.info(s"userThreadBatchQueue size: ${userThreadBatchQueue.size}")
            val batch = userThreadBatchQueue.dequeue()
            // Update records even if sending the email fails (avoid infinite failure loops)
            // todo(martin) persist failures to database
            batch.participantThreads map { userThread =>
              db.readWrite{ implicit session =>
                userThreadRepo.setNotificationEmailed(userThread.id.get, userThread.lastMsgFromOther)
              }
            }
            processingUserTreadBatch = true
            val fut = emailUnreadMessagesForUserThreadBatch(batch)
            fut.onFailure {
              case t: Throwable => airbrake.notify(s"Failure occurred during user thread batch processing: threadId = ${batch.threadId}}", t)
            }
            fut
          })
          tasks.onComplete { _ =>
            self ! EndUserThreadBatchProcessing
          }
        }
      }

    case SendNextNonUserEmails(num) =>
      log.info("Attempting to process next user emails")
      if (!processingNonUserThreadBatch) {
        if (nonUserThreadBatchQueue.isEmpty) {
          val nonUserThreadBatches = getNonUserThreadsToProcess()
          nonUserThreadBatches.foreach(nonUserThreadBatchQueue.enqueue(_))
        }
        if (nonUserThreadBatchQueue.nonEmpty) {
          val tasks = Future.sequence((1 to num).map { _ =>
            log.info(s"nonUserThreadBatchQueue size: ${nonUserThreadBatchQueue.size}")
            val batch = nonUserThreadBatchQueue.dequeue()
            processingNonUserThreadBatch = true
            val thread = db.readOnly{ implicit session => threadRepo.get(batch.threadId) }
            val fut = elizaEmailCommander.getThreadEmailData(thread) map { threadEmailData =>
              batch.participantThreads.foreach {
                case emailParticipantThread if emailParticipantThread.participant.kind == NonUserKinds.email => elizaEmailCommander.notifyEmailParticipant(emailParticipantThread, threadEmailData)
                case unsupportedNonUserThread => airbrake.notify(new UnsupportedOperationException(s"Cannot email non user ${unsupportedNonUserThread.participant}"))
              }
            }
            fut.onFailure {
              case t: Throwable => airbrake.notify(s"Failure occurred during non user thread batch processing: threadId = ${batch.threadId}}", t)
            }
            fut
          })
          tasks.onComplete { _ =>
            self ! EndNonUserThreadBatchProcessing
          }
        }
      }

    case EndUserThreadBatchProcessing =>
      processingUserTreadBatch = false
      self ! SendNextUserEmails(MAX_CONCURRENT_USER_THREAD_BATCHES)

    case EndNonUserThreadBatchProcessing =>
      processingNonUserThreadBatch = false
      self ! SendNextNonUserEmails(MAX_CONCURRENT_NON_USER_THREAD_BATCHES)

    case m => throw new UnsupportedActorMessage(m)
  }

  /**
   * Fetches user threads that need to receive an email update and put them in the queue
   */
  private def getUserThreadsToProcess(): Seq[ParticipantThreadBatch[UserThread]] = {
    val now = clock.now
    val lastNotifiedBefore = now.minus(MIN_TIME_BETWEEN_NOTIFICATIONS.toMillis)
    val unseenUserThreads = db.readOnly { implicit session =>
      userThreadRepo.getUserThreadsForEmailing(lastNotifiedBefore)
    }
    val notificationUpdatedAts = unseenUserThreads.map { t => t.id.get -> t.notificationUpdatedAt } toMap;
    log.info(s"[now:$now] [lastNotifiedBefore:$lastNotifiedBefore] found ${unseenUserThreads.size} unseenUserThreads, notificationUpdatedAt: ${notificationUpdatedAts.mkString(",")}")
    unseenUserThreads.groupBy(_.threadId).values.map(ParticipantThreadBatch(_)).toSeq
  }

  private def getNonUserThreadsToProcess(): Seq[ParticipantThreadBatch[NonUserThread]] = {
    val now = clock.now
    val lastNotifiedBefore = now.minus(MIN_TIME_BETWEEN_NOTIFICATIONS.toMillis)
    val lastUpdatedByOtherAfter = now.minus(RECENT_ACTIVITY_WINDOW.toMillis)
    val unseenNonUserThreads = db.readOnly { implicit session =>
      nonUserThreadRepo.getNonUserThreadsForEmailing(lastNotifiedBefore, lastUpdatedByOtherAfter)
    }
    unseenNonUserThreads.groupBy(_.threadId).values.map(ParticipantThreadBatch(_)).toSeq
  }

  /**
   * Sends email update to all specified user threads corresponding to the same MessageThread
   */
  private def emailUnreadMessagesForUserThreadBatch(batch: ParticipantThreadBatch[UserThread]): Future[Unit] = {
    val userThreads = batch.participantThreads
    val threadId = batch.threadId
    val thread = db.readOnly { implicit session => threadRepo.get(threadId) }
    val allUserIds = thread.participants.map(_.allUsers).getOrElse(Set()).toSeq
    val allUsersFuture : Future[Map[Id[User], User]] = new SafeFuture(
      shoebox.getUsers(allUserIds).map( s => s.map(u => u.id.get -> u).toMap)
    )
    val allUserImageUrlsFuture: Future[Map[Id[User], String]] = new SafeFuture(FutureHelpers.map(allUserIds.map(u => u -> shoebox.getUserImageUrl(u, 73)).toMap))
    val uriSummaryFuture = elizaEmailCommander.getSummarySmall(thread)
    val readTimeMinutesOptFuture = elizaEmailCommander.readTimeMinutesForMessageThread(thread)
    val threadDataFuture = for {
      allUsers <- allUsersFuture
      allUserImageUrls <- allUserImageUrlsFuture
      uriSummary <- uriSummaryFuture
      readTimeMinutesOpt <- readTimeMinutesOptFuture
    } yield (allUsers, allUserImageUrls, uriSummary, readTimeMinutesOpt)
    threadDataFuture.map { data =>
      val (allUsers, allUserImageUrls, uriSummary, readTimeMinutesOpt) = data
      // Futures below will be executed concurrently
      userThreads.foreach{ userThread =>
        emailUnreadMessagesForUserThread(userThread, thread, allUsers, allUserImageUrls, uriSummary, readTimeMinutesOpt)
      }
    }
  }

  private def emailUnreadMessagesForUserThread(
    userThread: UserThread,
    thread: MessageThread,
    allUsers: Map[Id[User], User],
    allUserImageUrls: Map[Id[User], String],
    uriSummary: URISummary,
    readTimeMinutesOpt: Option[Int]
  ): Future[Unit] = {
    log.info(s"processing user thread $userThread")
    val now = clock.now
    airbrake.verify(userThread.replyable,
      s"${userThread.summary} not replyable")
    airbrake.verify(userThread.unread,
      s"${userThread.summary} not unread")
    airbrake.verify(!userThread.notificationEmailed,
      s"${userThread.summary} notification already emailed")
    airbrake.verify(userThread.notificationUpdatedAt.isAfter(now.minusMinutes(30)),
      s"Late send (${Minutes.minutesBetween(now, userThread.notificationUpdatedAt)} min) of user thread ${userThread.summary} notificationUpdatedAt ${userThread.notificationUpdatedAt} ")
    airbrake.verify(userThread.notificationUpdatedAt.isBefore(now),
      s"${userThread.summary} notificationUpdatedAt ${userThread.notificationUpdatedAt} in the future (${Minutes.minutesBetween(userThread.notificationUpdatedAt, now)} min)")

    val extendedThreadItems: Seq[ExtendedThreadItem] = elizaEmailCommander.getExtendedThreadItems(thread, allUsers, allUserImageUrls, userThread.lastSeen, None)

    val fut = if (extendedThreadItems.nonEmpty) {
      log.info(s"preparing to send email for thread ${thread.id}, user thread ${thread.id} of user ${userThread.user} " +
        s"with notificationUpdatedAt=${userThread.notificationUpdatedAt} and notificationLastSeen=${userThread.notificationLastSeen} " +
        s"with ${extendedThreadItems.size} items and unread=${userThread.unread} and notificationEmailed=${userThread.notificationEmailed}")

      val recipientUserId = userThread.user
      val deepUrlFuture: Future[String] = shoebox.getDeepUrl(thread.deepLocator, recipientUserId)

      deepUrlFuture flatMap { deepUrl =>
        //if user is not active, skip it!
        val recipient = allUsers(recipientUserId)
        if (recipient.state == UserStates.ACTIVE && recipient.primaryEmailId.nonEmpty) {

          for {
            destinationEmail <- shoebox.getEmailAddressById(recipient.primaryEmailId.get)
            unsubUrl <- shoebox.getUnsubscribeUrlForEmail(destinationEmail)
          } yield {
            val threadEmailInfo: ThreadEmailInfo = elizaEmailCommander.getThreadEmailInfo(thread, uriSummary, false, allUsers, allUserImageUrls, None, Some(unsubUrl), None, readTimeMinutesOpt).copy(pageUrl = deepUrl)

            val magicAddress = EmailAddresses.discussion(userThread.accessToken.token)
            val email = ElectronicMail(
              from = magicAddress,
              fromName = Some("Kifi Notifications"),
              to = Seq(GenericEmailAddress(destinationEmail)),
              subject = s"""New messages on "${threadEmailInfo.pageTitle}"""",
              htmlBody = views.html.userDigestEmail(threadEmailInfo, extendedThreadItems).body,
              category = NotificationCategory.User.MESSAGE
            )
            shoebox.sendMail(email)
          }
        } else {
          log.warn(s"user $recipient is not active, not sending emails")
          Future.successful()
        }
      }
    } else Future.successful()
    log.info(s"processed user thread $userThread")
    fut map (_ => ())
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
    scheduleTaskOnLeader(actor.system, 30 seconds, 2 minutes, actor.ref, SendNextUserEmails(MAX_CONCURRENT_USER_THREAD_BATCHES))
    scheduleTaskOnLeader(actor.system, 90 seconds, 2 minutes, actor.ref, SendNextNonUserEmails(MAX_CONCURRENT_NON_USER_THREAD_BATCHES))
  }

  def sendEmails() {
    actor.ref ! SendNextUserEmails(MAX_CONCURRENT_USER_THREAD_BATCHES)
    actor.ref ! SendNextNonUserEmails(MAX_CONCURRENT_NON_USER_THREAD_BATCHES)
  }
}
