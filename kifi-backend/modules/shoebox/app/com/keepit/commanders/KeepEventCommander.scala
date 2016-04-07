package com.keepit.commanders

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.KeepEventData.{ ModifyRecipients, EditTitle }
import com.keepit.model.{ KeepRecipientsDiff, KeepRecipients, KeepEventData, KeepEvent, KeepEventRepo, KeepEventSourceKind, User, Keep }
import com.keepit.common.time._
import org.joda.time.DateTime

import scala.concurrent.ExecutionContext

@ImplementedBy(classOf[KeepEventCommanderImpl])
trait KeepEventCommander {
  def registerKeepEvent(keepId: Id[Keep], event: KeepEventData, source: Option[KeepEventSourceKind], eventTime: Option[DateTime])(implicit session: RWSession): Boolean
}

@Singleton
class KeepEventCommanderImpl @Inject() (
    eliza: ElizaServiceClient,
    eventRepo: KeepEventRepo,
    db: Database,
    implicit val ec: ExecutionContext) extends KeepEventCommander {

  def registerKeepEvent(keepId: Id[Keep], eventData: KeepEventData, source: Option[KeepEventSourceKind], eventTime: Option[DateTime])(implicit session: RWSession): Boolean = {
    val isValidEvent = eventData match {
      case ModifyRecipients(_, diff) if diff.isEmpty => false
      case _ => true
    }

    if (isValidEvent) {
      val event = KeepEvent(keepId = keepId, eventData = eventData, eventTime = eventTime.getOrElse(currentDateTime), source = source)
      eventRepo.save(event)
    }

    isValidEvent
  }
}
