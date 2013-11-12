package com.keepit.heimdal

import scala.concurrent.Future
import reactivemongo.bson._
import play.modules.reactivemongo.json.ImplicitBSONHandlers.JsValueReader
import com.keepit.serializer.Companion
import play.api.libs.json.JsArray
import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait EventRepo {
  type T <: HeimdalEvent
  def getEventCompanion: Companion[T]
  def getLatestRawEvents(eventsToConsider: EventSet, number: Int) : Future[JsArray]
}

abstract class MongoEventRepo[E <: HeimdalEvent: Companion] extends BufferedMongoRepo[E] with EventRepo {
  type T = E
  val getEventCompanion = implicitly[Companion[E]]
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

  def findByEventTypeCode(availableRepos: EventRepo*)(typeCode: String): Option[EventRepo] = availableRepos.find(_.getEventCompanion == HeimdalEvent.getCompanion(typeCode))
}

