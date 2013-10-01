package com.keepit.heimdal

import org.joda.time.DateTime

import play.api.libs.json.{JsObject, JsNull, JsArray}
import play.modules.reactivemongo.json.ImplicitBSONHandlers._

import scala.concurrent.duration._
import scala.concurrent.{Future, future}
import play.api.libs.concurrent.Execution.Implicits.defaultContext //Might want to change this to a custom play one

import reactivemongo.bson.{BSONDocument, BSONArray, BSONString, BSONDouble}

import com.google.inject.Inject


class MetricManager @Inject() (userEventLoggingRepo: UserEventLoggingRepo){

  def computeAdHocMteric(startTime: DateTime, endTime: DateTime, definition: MetricDefinition): Future[JsArray]  = {
    val pipeline = definition.aggregationForTimeWindow(startTime, Duration(endTime.getMillis - startTime.getMillis,"ms"))
    userEventLoggingRepo.performAggregation(pipeline).map{ bsonStream =>
      JsArray( bsonStream.toSeq.map { bson =>
        JsObjectReader.read(bson)
      })
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
    userEventLoggingRepo.collection.find(eventSelector).sort(sortOrder).cursor.collect[Seq](number).map{ events =>
      JsArray(events)
    }
  }

  //random change to make github realiza something happened

}
