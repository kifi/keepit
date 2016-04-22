package com.keepit.commanders

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.KeepEventData.{ EditTitle, ModifyRecipients }
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.shoebox.data.assemblers.KeepActivityAssembler
import org.joda.time.DateTime

import scala.concurrent.{ Future, ExecutionContext }

@ImplementedBy(classOf[KeepEventCommanderImpl])
trait KeepEventCommander {
  def persistKeepEventAndUpdateEliza(keepId: Id[Keep], event: KeepEventData, source: Option[KeepEventSource], eventTime: Option[DateTime])(implicit session: RWSession): Option[KeepEvent]
  def persistKeepEvent(keepId: Id[Keep], eventData: KeepEventData, source: Option[KeepEventSource], eventTime: Option[DateTime])(implicit session: RWSession): Option[KeepEvent]
}

@Singleton
class KeepEventCommanderImpl @Inject() (
  eliza: ElizaServiceClient,
  keepMutator: KeepMutator,
  keepActivityAssembler: KeepActivityAssembler,
  ktuRepo: KeepToUserRepo,
  eventRepo: KeepEventRepo,
  db: Database,
  clock: Clock,
  implicit val ec: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends KeepEventCommander {

  def persistKeepEventAndUpdateEliza(keepId: Id[Keep], eventData: KeepEventData, source: Option[KeepEventSource], eventTime: Option[DateTime])(implicit session: RWSession): Option[KeepEvent] = {
    val eventOpt = persistKeepEvent(keepId, eventData, source, eventTime)

    eventOpt.map { event =>
      val usersToSendTo = ktuRepo.getAllByKeepId(keepId).map(_.userId)
      val basicEvent = keepActivityAssembler.assembleBasicKeepEvent(keepId, event)
      session.onTransactionSuccess {
        eventData match {
          case mr: ModifyRecipients if mr.diff.users.added.nonEmpty || mr.diff.emails.added.nonEmpty || mr.diff.libraries.added.nonEmpty => eliza.editParticipantsOnKeep(keepId, mr.addedBy, mr.diff, source)
          case _ =>
        }
        broadcastKeepEvent(keepId, usersToSendTo.toSet, basicEvent)
      }
      basicEvent
    }

    eventOpt
  }

  def persistKeepEvent(keepId: Id[Keep], eventData: KeepEventData, source: Option[KeepEventSource], eventTime: Option[DateTime])(implicit session: RWSession): Option[KeepEvent] = {
    val time = eventTime getOrElse clock.now
    def brandNewEvent = eventRepo.save(KeepEvent(keepId = keepId, eventData = eventData, eventTime = time, source = source))
    val eventOpt = eventData match {
      case ModifyRecipients(_, diff) if diff.isEmpty => None
      case EditTitle(_, prev, cur) if prev == cur => None

      case mod @ ModifyRecipients(adder, diff) => Some(brandNewEvent)
      case edit @ EditTitle(curEditor, curPrev, curUpdated) =>
        val updatedEvent = eventRepo.getMostRecentForKeep(keepId).flatMap { lastEvent =>
          Some(lastEvent.eventData).collect {
            case EditTitle(lastEditor, lastPrev, lastUpdated) if lastEditor == curEditor && lastUpdated == curPrev =>
              eventRepo.save(lastEvent.withEventData(EditTitle(curEditor, lastPrev, curUpdated)).withEventTime(time))
          }
        }
        Some(updatedEvent getOrElse brandNewEvent)
    }
    if (eventOpt.isDefined) keepMutator.updateLastActivityAtIfLater(keepId, time)
    eventOpt
  }

  private def broadcastKeepEvent(keepId: Id[Keep], users: Set[Id[User]], event: BasicKeepEvent): Future[Unit] = {
    Future.sequence(users.map(uid => eliza.sendKeepEvent(uid, Keep.publicId(keepId), event))).map(_ => ())
  }
}
