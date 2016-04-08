package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.cache.{ ImmutableJsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ FortyTwoGenericTypeMappers, Repo, DBSession, DbRepo, DataBaseComponent }
import com.keepit.common.db.{ CommonClassLinker, ModelWithState, States, State, Id }
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.discussion.Message
import com.keepit.model.KeepEventData.{ EditTitle, ModifyRecipients }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class KeepEvent(
    id: Option[Id[KeepEvent]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[KeepEvent] = KeepEventStates.ACTIVE,
    keepId: Id[Keep],
    eventData: KeepEventData,
    eventTime: DateTime,
    source: Option[KeepEventSourceKind],
    // unique constraint on messageId to ensure message rows are not duplicated
    messageId: Option[Id[Message]] = None) extends ModelWithState[KeepEvent] {
  def withId(id: Id[KeepEvent]): KeepEvent = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): KeepEvent = this.copy(updatedAt = updatedAt)
}
object KeepEventStates extends States[KeepEvent]
object KeepEvent extends CommonClassLinker[KeepEvent, CommonKeepEvent] {
  def idsInvolved(events: Iterable[KeepEvent]): (Set[Id[User]], Set[Id[Library]]) = {
    events.foldLeft[(Set[Id[User]], Set[Id[Library]])]((Set.empty, Set.empty)) {
      case ((users, libs), event) =>

        val (newUsers, newLibs) = event.eventData match {
          case EditTitle(editedBy, _, _) => (Set(editedBy), Set.empty[Id[Library]])
          case ModifyRecipients(addedBy, diff) =>
            val (users, libs, _) = diff.allEntities
            (users + addedBy, libs)
        }

        (users ++ newUsers, libs ++ newLibs)
    }
  }

  def publicId(id: Id[KeepEvent])(implicit config: PublicIdConfiguration): PublicId[CommonKeepEvent] = CommonKeepEvent.publicId(KeepEvent.toCommonId(id))

  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[KeepEvent]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format[State[KeepEvent]] and
    (__ \ 'keepId).format(Id.format[Keep]) and
    (__ \ 'eventData).format[KeepEventData] and
    (__ \ 'eventTime).format[DateTime] and
    (__ \ 'source).formatNullable[KeepEventSourceKind] and
    (__ \ 'messageId).formatNullable[Id[Message]]
  )(KeepEvent.apply, unlift(KeepEvent.unapply))

  def fromDbRow(
    id: Option[Id[KeepEvent]],
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[KeepEvent] = KeepEventStates.ACTIVE,
    keepId: Id[Keep],
    eventData: KeepEventData,
    eventTime: DateTime,
    source: Option[KeepEventSourceKind],
    messageId: Option[Id[Message]]): KeepEvent = KeepEvent(id, createdAt, updatedAt, state, keepId, eventData, eventTime, source, messageId)

  def toDbRow(event: KeepEvent): Option[(Option[Id[KeepEvent]], DateTime, DateTime, State[KeepEvent], Id[Keep], KeepEventData, DateTime, Option[KeepEventSourceKind], Option[Id[Message]])] = {
    Some((event.id, event.createdAt, event.updatedAt, event.state, event.keepId, event.eventData, event.eventTime, event.source, event.messageId))
  }
}

@ImplementedBy(classOf[KeepEventRepoImpl])
trait KeepEventRepo extends Repo[KeepEvent] {
  def pageForKeep(keepId: Id[Keep], fromTime: Option[DateTime], limit: Int)(implicit session: RSession): Seq[KeepEvent]
  def getForKeeps(keepIds: Set[Id[Keep]], limit: Option[Int])(implicit s: RSession): Map[Id[Keep], Seq[KeepEvent]]
}

@Singleton
class KeepEventRepoImpl @Inject() (
    val clock: Clock,
    val db: DataBaseComponent) extends DbRepo[KeepEvent] with KeepEventRepo with FortyTwoGenericTypeMappers {
  import DBSession._
  import db.Driver.simple._

  implicit val keepEventDataTypeMapper = jsonMapper[KeepEventData]
  implicit val keepEventSourceTypeMapper = MappedColumnType.base[KeepEventSourceKind, String](_.value, KeepEventSourceKind.apply)

  type RepoImpl = KeepEventTable
  class KeepEventTable(tag: Tag) extends RepoTable[KeepEvent](db, tag, "keep_event") {
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def eventData = column[KeepEventData]("event_data", O.NotNull)
    def eventTime = column[DateTime]("event_time", O.NotNull)
    def source = column[Option[KeepEventSourceKind]]("source", O.Nullable)
    def messageId = column[Option[Id[Message]]]("message_id", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, keepId, eventData, eventTime, source, messageId) <> ((KeepEvent.fromDbRow _).tupled, KeepEvent.toDbRow)
  }

  def table(tag: Tag) = new KeepEventTable(tag)

  override def deleteCache(model: KeepEvent)(implicit session: RSession): Unit = ()
  override def invalidateCache(model: KeepEvent)(implicit session: RSession): Unit = ()

  private val activeRows = rows.filter(_.state === KeepEventStates.ACTIVE)

  def pageForKeep(keepId: Id[Keep], fromTime: Option[DateTime], limit: Int)(implicit session: RSession): Seq[KeepEvent] = {
    import com.keepit.common.util.Ord.dateTimeOrdering
    val keepRows = activeRows.filter(r => r.keepId === keepId)
    val rowsBefore = fromTime match {
      case None => keepRows
      case Some(time) => keepRows.filter(_.eventTime < fromTime)
    }
    rowsBefore.sortBy(_.eventTime desc).take(limit).list
  }

  def getForKeeps(keepIds: Set[Id[Keep]], limit: Option[Int])(implicit s: RSession): Map[Id[Keep], Seq[KeepEvent]] = {
    import com.keepit.common.core._
    // todo(cam): be more efficient here by perhaps using Ryan's fancy query for MessageRepo.getRecentByKeeps (i.e. don't paginate in-memory)
    activeRows.filter(r => r.keepId.inSet(keepIds)).list.groupBy(_.keepId).mapValuesStrict(_.sortBy(_.eventTime.getMillis * -1).take(limit.getOrElse(Int.MaxValue)))
  }
}
