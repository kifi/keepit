package com.keepit.eliza.mail

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.eliza.commanders.ElizaEmailCommander
import com.keepit.eliza.model._
import com.keepit.eliza.model.NonUserThread
import com.keepit.social.NonUserKinds
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.time._

class ElizaNonUserEmailNotifierActor @Inject() (
  airbrake: AirbrakeNotifier,
  db: Database,
  clock: Clock,
  elizaEmailCommander: ElizaEmailCommander,
  nonUserThreadRepo: NonUserThreadRepo,
  threadRepo: MessageThreadRepo
) extends ElizaEmailNotifierActor[NonUserThread](airbrake) {

  import ElizaEmailNotifierActor._

  protected def emailUnreadMessagesForParticipantThreadBatch(batch: ParticipantThreadBatch[NonUserThread]): Future[Unit] = {
    val thread = db.readOnly { implicit session => threadRepo.get(batch.threadId) }
    elizaEmailCommander.getThreadEmailData(thread) map { threadEmailData =>
      batch.participantThreads.foreach {
        case emailParticipantThread if emailParticipantThread.participant.kind == NonUserKinds.email => elizaEmailCommander.notifyEmailParticipant(emailParticipantThread, threadEmailData)
        case unsupportedNonUserThread => airbrake.notify(new UnsupportedOperationException(s"Cannot email non user ${unsupportedNonUserThread.participant}"))
      }
    }
  }

  /**
   * Fetches non user threads that need to receive an email update
   */

  protected def getParticipantThreadsToProcess: Seq[NonUserThread] = {
    val now = clock.now
    val lastNotifiedBefore = now.minus(MIN_TIME_BETWEEN_NOTIFICATIONS.toMillis)
    val lastUpdatedByOtherAfter = now.minus(RECENT_ACTIVITY_WINDOW.toMillis)
    val unseenNonUserThreads = db.readOnly { implicit session =>
      nonUserThreadRepo.getNonUserThreadsForEmailing(lastNotifiedBefore, lastUpdatedByOtherAfter)
    }
    unseenNonUserThreads
  }
}