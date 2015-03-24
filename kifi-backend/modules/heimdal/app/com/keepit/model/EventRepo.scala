package com.keepit.model

import com.keepit.common.logging.Logging
import com.keepit.common.time._
import CustomBSONHandlers.{ BSONDateTimeHandler, BSONEventContextHandler, BSONEventTypeHandler }
import com.keepit.heimdal._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, Json }
import play.modules.reactivemongo.json.ImplicitBSONHandlers
import reactivemongo.bson._
import reactivemongo.core.commands.PipelineOperator

import scala.concurrent.{ Future, Promise }

trait EventRepo[E <: HeimdalEvent] {
  def persist(event: E): Future[Unit]
  def getEventCompanion: HeimdalEventCompanion[E]
  def getLatestRawEvents(eventsToConsider: EventSet, number: Int, window: Int): Future[JsArray]
  def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]]
  def descriptors: EventDescriptorRepo[E]
}

trait EventAugmentor[E <: HeimdalEvent] extends PartialFunction[E, Future[Seq[(String, ContextData)]]]

object EventAugmentor extends Logging {
  def safelyAugmentContext[E <: HeimdalEvent](event: E, augmentors: EventAugmentor[E]*): Future[HeimdalContext] = {
    val safeAugmentations = augmentors.collect {
      case augmentor if augmentor.isDefinedAt(event) =>
        augmentor(event) recover {
          case ex =>
            log.error(s"An augmentor failed on event: ${event.eventType}", ex)
            Seq.empty
        }
    }

    Future.sequence(safeAugmentations).map { augmentations =>
      val contextBuilder = new HeimdalContextBuilder()
      contextBuilder.data ++= event.context.data
      augmentations.foreach(contextBuilder.data ++= _)
      contextBuilder.build
    }
  }
}

abstract class MongoEventRepo[E <: HeimdalEvent: HeimdalEventCompanion] extends BufferedMongoRepo[E] with EventRepo[E] {
  val getEventCompanion = implicitly[HeimdalEventCompanion[E]]
  val mixpanel: MixpanelClient
  def persist(event: E): Future[Unit] = {
    if (event.time.isBefore(currentDateTime.minusYears(1)))
      airbrake.notify(s"Unexpected HeimdalEvent.time is over 1 year old: $event")

    val insertF = insert(event)
    val trackF = descriptors.getByName(event.eventType) flatMap {
      case None => descriptors.upsert(EventDescriptor(event.eventType)) map (_ => ())
      case Some(description) if description.mixpanel => mixpanel.track(event)
    }

    insertF flatMap (_ => trackF)
  }

  def getLatestRawEvents(eventsToConsider: EventSet, number: Int, window: Int): Future[JsArray] = {
    val eventSelector = eventsToConsider.toBSONMatchDocument ++ ("time" -> BSONDocument("$gt" -> BSONDateTime(currentDateTime.minusHours(window).getMillis)))
    val sortOrder = BSONDocument("time" -> BSONDouble(-1.0))
    collection.find(eventSelector).sort(sortOrder).cursor.collect[Seq](number).map { events =>
      JsArray(events.map(ImplicitBSONHandlers.JsValueReader.read))
    }
  }
}

abstract class DevEventRepo[E <: HeimdalEvent: HeimdalEventCompanion] extends EventRepo[E] {
  val getEventCompanion = implicitly[HeimdalEventCompanion[E]]
  def persist(event: E): Future[Unit] = Future.successful(())
  def getLatestRawEvents(eventsToConsider: EventSet, number: Int, window: Int): Future[JsArray] = Future.successful(Json.arr())
  def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]] = {
    Promise.successful(
      Stream(BSONDocument("command" -> BSONArray(command.map(_.makePipe))))
    ).future
  }
  lazy val descriptors: EventDescriptorRepo[E] = new DevEventDescriptorRepo[E] {}
}

object EventRepo {
  def findByEventTypeCode(availableRepos: EventRepo[_ <: HeimdalEvent]*)(code: String): Option[EventRepo[_ <: HeimdalEvent]] = availableRepos.find(_.getEventCompanion == HeimdalEventCompanion.byTypeCode(code))
  def eventToBSONFields(event: HeimdalEvent): Seq[(String, BSONValue)] = Seq(
    "context" -> BSONEventContextHandler.write(event.context),
    "eventType" -> BSONEventTypeHandler.write(event.eventType),
    "time" -> BSONDateTimeHandler.write(event.time)
  )
}

