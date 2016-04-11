package com.keepit.commanders

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.KeepEventData.ModifyRecipients
import com.keepit.model.{ BasicKeepEvent, KeepToUserRepo, KeepEventData, KeepEvent, KeepEventRepo, KeepEventSourceKind, User, Keep }
import com.keepit.common.time._
import com.keepit.shoebox.data.assemblers.KeepActivityAssembler
import org.joda.time.DateTime

import scala.concurrent.{ Future, ExecutionContext }

@ImplementedBy(classOf[KeepEventCommanderImpl])
trait KeepEventCommander {
  def registerKeepEvent(keepId: Id[Keep], event: KeepEventData, source: Option[KeepEventSourceKind], eventTime: Option[DateTime])(implicit session: RWSession): Boolean
}

@Singleton
class KeepEventCommanderImpl @Inject() (
    eliza: ElizaServiceClient,
    keepMutator: KeepMutator,
    keepActivityAssembler: KeepActivityAssembler,
    ktuRepo: KeepToUserRepo,
    eventRepo: KeepEventRepo,
    db: Database,
    implicit val ec: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) extends KeepEventCommander {

  def registerKeepEvent(keepId: Id[Keep], eventData: KeepEventData, source: Option[KeepEventSourceKind], eventTime: Option[DateTime])(implicit session: RWSession): Boolean = {
    val isValidEvent = eventData match {
      case ModifyRecipients(_, diff) if diff.isEmpty => false
      case _ => true
    }

    if (isValidEvent) {
      val time = eventTime.getOrElse(currentDateTime)
      val event = eventRepo.save(KeepEvent(keepId = keepId, eventData = eventData, eventTime = time, source = source))
      keepMutator.updateLastActivityAtIfLater(keepId, time)

      val usersToSendTo = ktuRepo.getAllByKeepId(keepId).map(_.userId)
      val basicEvent = keepActivityAssembler.assembleBasicKeepEvent(keepId, event)
      session.onTransactionSuccess {
        eventData match {
          case mr: ModifyRecipients if mr.diff.users.added.nonEmpty || mr.diff.emails.added.nonEmpty => eliza.syncAddParticipants(keepId, mr, source)
          case _ =>
        }
        broadcastKeepEvent(keepId, usersToSendTo.toSet, basicEvent)
      }
    }

    isValidEvent
  }

  def broadcastKeepEvent(keepId: Id[Keep], users: Set[Id[User]], event: BasicKeepEvent): Future[Unit] = {
    Future.sequence(users.map(uid => eliza.sendKeepEvent(uid, Keep.publicId(keepId), event))).map(_ => ())
  }
}
