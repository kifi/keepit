package com.keepit.heimdal

import reactivemongo.core.commands.{LastError, PipelineOperator}
import reactivemongo.bson.{BSONDocument, BSONArray, Macros}

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.{Future, Promise}
import com.keepit.common.db._
import org.joda.time.DateTime
import com.keepit.common.time._
import CustomBSONHandlers._
import com.keepit.common.akka.SafeFuture
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.db.States

case class EventDescriptor[E <: HeimdalEvent](
  name: EventType,
  description: Option[String] = None,
  mixpanel: State[HeimdalEvent] = MixpanelStates.PENDING,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime
) {
  def sendToMixpanel: Boolean = mixpanel == MixpanelStates.ACTIVE || (mixpanel == MixpanelStates.PENDING && createdAt.isBefore(currentDateTime.minusDays(7)))
}

object MixpanelStates extends States[HeimdalEvent] {
  val PENDING = State[HeimdalEvent]("pending")
}

object EventDescriptor {
  implicit def bsonHandler[E <: HeimdalEvent] = Macros.handler[EventDescriptor[E]]
  implicit def format[E <: HeimdalEvent]: Format[EventDescriptor[E]] = (
    (__ \ 'name).format[EventType] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'mixpanel).format(State.format[HeimdalEvent]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat)
  )(EventDescriptor.apply[E], unlift(EventDescriptor.unapply[E]))
}

trait EventDescriptorRepo[E <: HeimdalEvent] {
  def upsert(descriptor: EventDescriptor[E]) : Future[Int]
  def getByName(name: EventType): Future[Option[EventDescriptor[E]]]
}

trait ProdEventDescriptorRepo[E <: HeimdalEvent] extends MongoRepo[EventDescriptor[E]] with EventDescriptorRepo[E] {

  val handler = EventDescriptor.bsonHandler

  def toBSON(obj: EventDescriptor[E]): BSONDocument = EventDescriptor.bsonHandler.write(obj)
  def fromBSON(doc: BSONDocument): EventDescriptor[E] = EventDescriptor.bsonHandler.read(doc)

  override def insert(obj: EventDescriptor[E], dropDups: Boolean = false) : Unit = { upsert(obj) } // Do not allow unchecked inserts of descriptors
  def upsert(obj: EventDescriptor[E]) : Future[Int] = new SafeFuture(
      collection.update(
        selector = BSONDocument("name" -> obj.name),
        update = toBSON(obj.copy(updatedAt = currentDateTime)),
        upsert = true,
        multi = false
      ) map { lastError => if (lastError.inError) throw lastError.getCause else lastError.updated }
    )

  def getByName(name: EventType): Future[Option[EventDescriptor[E]]] = {
    collection.find(BSONDocument("name" -> name)).one.map{
      _.map(fromBSON(_))
    }
  }
}

trait DevEventDescriptorRepo[E <: HeimdalEvent] extends EventDescriptorRepo[E] {
  def upsert(obj: EventDescriptor[E]) : Future[Int] = Future.failed(new NotImplementedError)
  def getByName(name: EventType): Future[Option[EventDescriptor[E]]] = Future.successful(None)
}
