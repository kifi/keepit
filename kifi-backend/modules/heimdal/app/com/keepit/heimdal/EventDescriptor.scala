package com.keepit.heimdal

import com.keepit.common.healthcheck.AirbrakeNotifier

import reactivemongo.core.commands.{LastError, PipelineOperator}
import reactivemongo.bson.{BSONDocument, BSONArray, Macros}

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.{Future, Promise}
import com.keepit.common.db.{States, State}
import org.joda.time.DateTime
import com.keepit.common.time._
import reactivemongo.api.collections.default.BSONCollection
import CustomBSONHandlers._
import com.keepit.common.akka.SafeFuture

case class EventDescriptor[E <: HeimdalEvent](
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  name: EventType,
  description: String,
  mixpanel: State[HeimdalEvent] = MixpanelStates.PENDING)

object MixpanelStates extends States[HeimdalEvent] {
  val PENDING = State[HeimdalEvent]("pending")
}

trait EventDescriptorRepo[E <: HeimdalEvent] extends MongoRepo[EventDescriptor[E]] {

  val bsonHandler = Macros.handler[EventDescriptor[E]]

  def toBSON(obj: EventDescriptor[E]): BSONDocument = bsonHandler.write(obj)
  def fromBSON(doc: BSONDocument): EventDescriptor[E] = bsonHandler.read(doc)

  def upsert(obj: EventDescriptor[E]) : Future[LastError]

  def getByName(name: EventType): Future[Option[EventDescriptor[E]]] = {
    collection.find(BSONDocument("name" -> name)).one.map{
      _.map(fromBSON(_))
    }
  }
}

trait ProdEventDescriptorRepo[E <: HeimdalEvent] extends EventDescriptorRepo[E] {
  def upsert(obj: EventDescriptor[E]) : Future[LastError] = new SafeFuture(
    collection.update(
      selector = BSONDocument("name" -> obj.name),
      update = toBSON(obj),
      upsert = true,
      multi = false
    )
  )
}

trait DevEventDescriptorRepo[E <: HeimdalEvent] extends EventDescriptorRepo[E] {
  def upsert(obj: EventDescriptor[E]) : Future[LastError] = Future.failed(new NotImplementedError)
  override def insert(obj: EventDescriptor[E], dropDups: Boolean = false) : Unit = {}
  override def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]] = {
    Promise.successful(
      Stream(BSONDocument("command" -> BSONArray(command.map(_.makePipe))))
    ).future
  }
}
