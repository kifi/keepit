package com.keepit.export

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.logging.{ Logging, SlackLog }
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.slack.InhouseSlackChannel
import org.joda.time.{ Duration, DateTime }

@ImplementedBy(classOf[FullExportSchedulerImpl])
trait FullExportScheduler {
  def getExportRequest(userId: Id[User])(implicit session: RSession): Option[FullExportRequest]
  def internExportRequest(userId: Id[User])(implicit session: RWSession): FullExportRequest
}

object FullExportSchedulerConfig {
  val COOLDOWN_AFTER_SUCCESSFUL_EXPORT = Duration.standardHours(24)

  def oldEnoughToBeReprocessed(old: FullExportRequest, now: DateTime): Boolean = {
    old.status match {
      case FullExportStatus.Finished(_, finishedAt, _) =>
        val cameOffCooldown = finishedAt plus COOLDOWN_AFTER_SUCCESSFUL_EXPORT
        cameOffCooldown isBefore now
      case _ => false // any other existing requests will be automatically (re)processed as necessary
    }
  }
}

@Singleton
class FullExportSchedulerImpl @Inject() (
  requestRepo: FullExportRequestRepo,
  clock: Clock)
    extends FullExportScheduler with Logging {
  import FullExportSchedulerConfig._

  def getExportRequest(userId: Id[User])(implicit session: RSession): Option[FullExportRequest] = {
    requestRepo.getByUser(userId)
  }

  def internExportRequest(userId: Id[User])(implicit session: RWSession): FullExportRequest = {
    requestRepo.getByUser(userId).filterNot(oldEnoughToBeReprocessed(_, clock.now)).getOrElse {
      requestRepo.save(FullExportRequest(userId = userId, status = FullExportStatus.NotStarted))
    }
  }
}
