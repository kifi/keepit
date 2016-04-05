package com.keepit.commanders

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.{ KeepRecipients, KeepEventData, KeepEvent, KeepEventRepo, KeepEventSourceKind, User, Keep }
import com.keepit.common.time._

import scala.concurrent.ExecutionContext

@ImplementedBy(classOf[KeepEventCommanderImpl])
trait KeepEventCommander {
  def updatedKeepTitle(keepId: Id[Keep], userId: Id[User], oldTitle: Option[String], newTitle: Option[String], source: Option[KeepEventSourceKind])(implicit session: RWSession): Unit
  def addedRecipients(keepId: Id[Keep], addedBy: Id[User], recipients: KeepRecipients, source: Option[KeepEventSourceKind])(implicit session: RWSession): Unit
}

@Singleton
class KeepEventCommanderImpl @Inject() (
    eliza: ElizaServiceClient,
    eventRepo: KeepEventRepo,
    db: Database,
    implicit val ec: ExecutionContext) extends KeepEventCommander {
  def updatedKeepTitle(keepId: Id[Keep], userId: Id[User], oldTitle: Option[String], newTitle: Option[String], source: Option[KeepEventSourceKind])(implicit session: RWSession): Unit = {
    val eventData = KeepEventData.EditTitle(userId, oldTitle, newTitle)
    val event = KeepEvent(keepId = keepId, eventData = eventData, eventTime = currentDateTime, source = source)
    eventRepo.save(event)
    session.onTransactionSuccess(eliza.saveKeepEvent(keepId, userId, eventData, source))
  }

  def addedRecipients(keepId: Id[Keep], addedBy: Id[User], recipients: KeepRecipients, source: Option[KeepEventSourceKind])(implicit session: RWSession): Unit = {
    val eventData = KeepEventData.AddRecipients(addedBy, recipients)
    val event = KeepEvent(keepId = keepId, eventData = eventData, eventTime = currentDateTime, source = source)
    eventRepo.save(event)
  }
}
