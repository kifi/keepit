package com.keepit.heimdal

import org.joda.time.DateTime

import play.api.libs.json.{JsObject, JsNull, JsArray}
import play.modules.reactivemongo.json.ImplicitBSONHandlers._

import scala.concurrent.duration._
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext //Might want to change this to a custom play one


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

}
