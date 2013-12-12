package com.keepit.heimdal

import scala.concurrent.{Promise, Future}
import reactivemongo.bson._
import play.modules.reactivemongo.json.ImplicitBSONHandlers.JsValueReader
import com.keepit.serializer.TypeCode
import play.api.libs.json.{Json, JsArray}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import reactivemongo.core.commands.PipelineOperator
import com.keepit.common.time._
import com.keepit.heimdal.CustomBSONHandlers.{BSONDateTimeHandler, BSONEventTypeHandler, BSONEventContextHandler}
import com.keepit.common.logging.Logging

trait EventRepo[E <: HeimdalEvent] {
  def persist(event: E) : Unit
  def getEventTypeCode: TypeCode[E]
  def getLatestRawEvents(eventsToConsider: EventSet, number: Int, window: Int) : Future[JsArray]
  def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]]
  def descriptors: EventDescriptorRepo[E]
}

trait EventAugmentor[E <: HeimdalEvent] {
  def augment(event: E): Future[Seq[(String, ContextData)]]
}

object EventAugmentor extends Logging {
  def safelyAugmentContext[E <: HeimdalEvent](event: E, augmentors: EventAugmentor[E]*): Future[HeimdalContext] = {
    val safeAugmentations = augmentors.map { augmentor =>
      augmentor.augment(event) recover { case ex =>
        log.error(s"An augmentor failed on event: ${event.eventType}", ex)
        Seq.empty
      }
    }
    Future.sequence(safeAugmentations).map { augmentations =>
      val contextBuilder = new HeimdalContextBuilder()
      contextBuilder.data ++ event.context.data
      augmentations.foreach(contextBuilder.data ++ _)
      contextBuilder.build
    }
  }
}

abstract class MongoEventRepo[E <: HeimdalEvent: TypeCode] extends BufferedMongoRepo[E] with EventRepo[E] {
  val getEventTypeCode = implicitly[TypeCode[E]]
  val mixpanel: MixpanelClient
  def persist(event: E): Unit = {
    insert(event)
    descriptors.getByName(event.eventType) map {
      case None => descriptors.upsert(EventDescriptor(event.eventType))
      case Some(description) if description.mixpanel => mixpanel.track(event)
    }
  }

  def getLatestRawEvents(eventsToConsider: EventSet, number: Int, window: Int) : Future[JsArray] = {
    val eventSelector = eventsToConsider.toBSONMatchDocument ++ ("time" -> BSONDocument("$gt" -> BSONDateTime(currentDateTime.minusHours(window).getMillis)))
    val sortOrder = BSONDocument("time" -> BSONDouble(-1.0))
    collection.find(eventSelector).sort(sortOrder).cursor.collect[Seq](number).map { events =>
      JsArray(events.map(JsValueReader.read))
    }
  }
}

abstract class DevEventRepo[E <: HeimdalEvent: TypeCode] extends EventRepo[E] {
  val getEventTypeCode = implicitly[TypeCode[E]]
  def persist(event: E): Unit = {}
  def getLatestRawEvents(eventsToConsider: EventSet, number: Int, window: Int) : Future[JsArray] = Future.successful(Json.arr())
  def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]] = {
    Promise.successful(
      Stream(BSONDocument("command" -> BSONArray(command.map(_.makePipe))))
    ).future
  }
  lazy val descriptors: EventDescriptorRepo[E] = new DevEventDescriptorRepo[E] {}
}

object EventRepo {
  def findByEventTypeCode(availableRepos: EventRepo[_ <: HeimdalEvent]*)(code: String): Option[EventRepo[_ <: HeimdalEvent]] = availableRepos.find(_.getEventTypeCode == HeimdalEvent.getTypeCode(code))
  def eventToBSONFields(event: HeimdalEvent): Seq[(String, BSONValue)] = Seq(
    "context" -> BSONEventContextHandler.write(event.context),
    "eventType" -> BSONEventTypeHandler.write(event.eventType),
    "time" -> BSONDateTimeHandler.write(event.time)
  )
}

