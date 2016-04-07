package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.cache.{ ImmutableJsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.slick.{ FortyTwoGenericTypeMappers, Repo, DBSession, DbRepo, DataBaseComponent }
import com.keepit.common.db.{ ModelWithState, States, State, Id }
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
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
    source: Option[KeepEventSourceKind]) extends ModelWithState[KeepEvent] {
  def withId(id: Id[KeepEvent]): KeepEvent = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): KeepEvent = this.copy(updatedAt = updatedAt)
}
object KeepEventStates extends States[KeepEvent]
object KeepEvent {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[KeepEvent]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format[State[KeepEvent]] and
    (__ \ 'keepId).format(Id.format[Keep]) and
    (__ \ 'eventData).format[KeepEventData] and
    (__ \ 'eventTime).format[DateTime] and
    (__ \ 'source).formatNullable[KeepEventSourceKind]
  )(KeepEvent.apply, unlift(KeepEvent.unapply))

  def fromDbRow(
    id: Option[Id[KeepEvent]],
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[KeepEvent] = KeepEventStates.ACTIVE,
    keepId: Id[Keep],
    eventData: KeepEventData,
    eventTime: DateTime,
    source: Option[KeepEventSourceKind]): KeepEvent = KeepEvent(id, createdAt, updatedAt, state, keepId, eventData, eventTime, source)

  def toDbRow(event: KeepEvent): Option[(Option[Id[KeepEvent]], DateTime, DateTime, State[KeepEvent], Id[Keep], KeepEventData, DateTime, Option[KeepEventSourceKind])] = {
    Some(event.id, event.createdAt, event.updatedAt, event.state, event.keepId, event.eventData, event.eventTime, event.source)
  }
}

@ImplementedBy(classOf[KeepEventRepoImpl])
trait KeepEventRepo extends Repo[KeepEvent]

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

    def * = (id.?, createdAt, updatedAt, state, keepId, eventData, eventTime, source) <> ((KeepEvent.fromDbRow _).tupled, KeepEvent.toDbRow)
  }

  def table(tag: Tag) = new KeepEventTable(tag)

  override def deleteCache(model: KeepEvent)(implicit session: RSession): Unit = ()
  override def invalidateCache(model: KeepEvent)(implicit session: RSession): Unit = ()
}