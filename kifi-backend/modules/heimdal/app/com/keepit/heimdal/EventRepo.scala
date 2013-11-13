package com.keepit.heimdal

import scala.concurrent.{Promise, Future}
import reactivemongo.bson._
import play.modules.reactivemongo.json.ImplicitBSONHandlers.JsValueReader
import com.keepit.serializer.TypeCode
import play.api.libs.json.{Json, JsArray}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import reactivemongo.core.commands.PipelineOperator

trait EventRepo[E <: HeimdalEvent] {
  def persist(event: E) : Unit
  def getEventTypeCode: TypeCode[E]
  def getLatestRawEvents(eventsToConsider: EventSet, number: Int) : Future[JsArray]
  def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]]
}

abstract class MongoEventRepo[E <: HeimdalEvent: TypeCode] extends BufferedMongoRepo[E] with EventRepo[E] {
  val getEventTypeCode = implicitly[TypeCode[E]]

  val descriptorRepo: EventDescriptorRepo[E]
  val mixpanel: MixpanelClient

  def persist(event: E): Unit = {
    insert(event)
    descriptorRepo.getByName(event.eventType) map {
      case None => descriptorRepo.upsert(EventDescriptor(event.eventType))
      case Some(description) => if (description.sendToMixpanel) mixpanel.send(event)
    }
  }

  def getLatestRawEvents(eventsToConsider: EventSet, number: Int) : Future[JsArray] = {
    val eventSelector = eventsToConsider match {
      case SpecificEventSet(events) =>
        BSONDocument(
          "event_type" -> BSONDocument(
            "$in" -> BSONArray(events.toSeq.map(eventType => BSONString(eventType.name)))
          )
        )
      case AllEvents => BSONDocument()
    }
    val sortOrder = BSONDocument("time" -> BSONDouble(-1.0))
    collection.find(eventSelector).sort(sortOrder).cursor.collect[Seq](number).map { events =>
      JsArray(events.map(JsValueReader.read))
    }
  }
}

abstract class DevEventRepo[E <: HeimdalEvent: TypeCode] extends EventRepo[E] {
  val getEventTypeCode = implicitly[TypeCode[E]]
  def persist(event: E): Unit = {}
  def getLatestRawEvents(eventsToConsider: EventSet, number: Int) : Future[JsArray] = Future.successful(Json.arr())
  def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]] = {
    Promise.successful(
      Stream(BSONDocument("command" -> BSONArray(command.map(_.makePipe))))
    ).future
  }
}

object EventRepo {

  private def contextToBSON(context: EventContext): BSONDocument = {
    BSONDocument(
      context.data.mapValues{ seq =>
        BSONArray(
          seq.map{ _ match {
            case ContextStringData(s)  => BSONString(s)
            case ContextDoubleData(x) => BSONDouble(x)
          }}
        )
      }
    )
  }

  def eventToBSONFields(event: HeimdalEvent): Seq[(String, BSONValue)] = Seq(
    "context" -> contextToBSON(event.context),
    "event_type" -> BSONString(event.eventType.name),
    "time" -> BSONDateTime(event.time.getMillis)
  )

  def findByEventTypeCode(availableRepos: EventRepo[_ <: HeimdalEvent]*)(typeCode: String): Option[EventRepo[_]] = availableRepos.find(_.getEventTypeCode.code == typeCode)
}

