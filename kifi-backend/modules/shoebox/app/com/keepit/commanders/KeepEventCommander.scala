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
  def persistKeepEventAndUpdateEliza(keepId: Id[Keep], event: KeepEventData, source: Option[KeepEventSource], eventTime: Option[DateTime])(implicit session: RWSession): Future[Unit]
  def persistAndAssembleKeepEvent(keepId: Id[Keep], event: KeepEventData, source: Option[KeepEventSource], eventTime: Option[DateTime])(implicit session: RWSession): Option[CommonAndBasicKeepEvent]
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

  def persistKeepEventAndUpdateEliza(keepId: Id[Keep], event: KeepEventData, source: Option[KeepEventSource], eventTime: Option[DateTime])(implicit session: RWSession): Future[Unit] = {
    persistAndAssembleKeepEvent(keepId, event, source, eventTime).map {
      case CommonAndBasicKeepEvent(commonEvent, basicEvent) => event match {
        case et: EditTitle => eliza.modifyRecipientsAndSendEvent(keepId, et.editedBy, KeepRecipientsDiff.addUser(et.editedBy), source, Some(basicEvent))
        case mr: ModifyRecipients => eliza.modifyRecipientsAndSendEvent(keepId, mr.addedBy, mr.diff, source, Some(basicEvent))
        case _ => Future.successful(())
      }
    }.getOrElse(Future.successful(None))
  }

  def persistAndAssembleKeepEvent(keepId: Id[Keep], event: KeepEventData, source: Option[KeepEventSource], eventTime: Option[DateTime])(implicit session: RWSession): Option[CommonAndBasicKeepEvent] = {
    persistKeepEvent(keepId, event, source, eventTime).map { event =>
      CommonAndBasicKeepEvent(KeepEvent.toCommonKeepEvent(event), keepActivityAssembler.assembleBasicKeepEvent(event))
    }
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
