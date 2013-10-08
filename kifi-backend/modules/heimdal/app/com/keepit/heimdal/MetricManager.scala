package com.keepit.heimdal

import org.joda.time.DateTime

import com.keepit.common.time._

import play.api.libs.json.{JsObject, JsNull, JsArray, Json}
import play.modules.reactivemongo.json.ImplicitBSONHandlers._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.duration._
import scala.concurrent.{Future, future, Await}


import reactivemongo.bson.{BSONDocument, BSONArray, BSONString, BSONDouble}

import com.google.inject.Inject


case class MetricDescriptor(name: String, start: DateTime, window: Int, step: Int, description: String, events: Seq[String], groupBy: String, breakDown: Boolean, mode: String, filter: String, lastUpdate: DateTime)

object MetricDescriptor {
  implicit val format = Json.format[MetricDescriptor]
}

class MetricManager @Inject() (userEventLoggingRepo: UserEventLoggingRepo, metricDescriptorRepo: MetricDescriptorRepo, metricRepoFactory: MetricRepoFactory){

  val definedRestrictions = Map[String, ContextRestriction](
    "none" -> NoContextRestriction,
    "noadmins" -> AnyContextRestriction("context.experiment", NotEqualTo(ContextStringData("admin")))
  )

  def computeAdHocMteric(startTime: DateTime, endTime: DateTime, definition: MetricDefinition): Future[JsArray]  = {
    val (pipeline, postprocess) = definition.aggregationForTimeWindow(startTime, Duration(endTime.getMillis - startTime.getMillis,"ms"))
    userEventLoggingRepo.performAggregation(pipeline).map{ bsonStream =>
      JsArray( postprocess(bsonStream).map { bson =>
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

  def createMetric(descriptor: MetricDescriptor): Unit = {
    metricDescriptorRepo.upsert(descriptor)
    metricRepoFactory.clear(descriptor.name)
  }

  def updateMetricOnce(desc: MetricDescriptor): MetricDescriptor = {
    val tStart = desc.lastUpdate.minusHours(desc.window).plusHours(desc.step)
    val tEnd = desc.lastUpdate.plusHours(desc.step)
    val eventsToConsider = if (desc.events.isEmpty) AllEvents else SpecificEventSet(desc.events.toSet.map( (s: String) => UserEventType(s)) )
    val contextRestriction = definedRestrictions(desc.filter)
    val definition = if(desc.mode=="users") {
      new GroupedUserCountMetricDefinition(eventsToConsider, contextRestriction, EventGrouping(desc.groupBy), if (desc.groupBy.startsWith("context")) desc.breakDown else false) 
    } else {
      new GroupedEventCountMetricDefinition(eventsToConsider, contextRestriction, EventGrouping(desc.groupBy), if (desc.groupBy.startsWith("context")) desc.breakDown else false) 
    }
    val (pipeline, postprocess) = definition.aggregationForTimeWindow(tStart, Duration(tEnd.getMillis - tStart.getMillis,"ms"))
    val data: Seq[BSONDocument] = Await.result(userEventLoggingRepo.performAggregation(pipeline).map(postprocess(_)), 5 minutes)
    val metricData = MetricData(tEnd, data)
    val repo = metricRepoFactory(desc.name)
    repo.insert(metricData)
    val newDesc = desc.copy(lastUpdate=tEnd)
    metricDescriptorRepo.upsert(desc)
    newDesc
  }

  def updateMetricFully(descriptor: MetricDescriptor): Unit = {
    val now = currentDateTime
    var desc = descriptor 
    while(now.isAfter(desc.start.plusHours(desc.window)) && now.isAfter(desc.lastUpdate.plusHours(desc.step))){
      try {
        desc = updateMetricOnce(desc)
        } catch {
          case x: Throwable => println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$"); println(x); println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$"); throw x;
        }
    }
  }

  def updateAllMetrics(): Unit = synchronized {
    val descriptorsFuture : Future[Seq[MetricDescriptor]] = metricDescriptorRepo.all
    descriptorsFuture.map{ descriptors =>
      descriptors.foreach(updateMetricFully(_))
    }
  }

  def getAvailableMetrics: Future[Seq[MetricDescriptor]] = metricDescriptorRepo.all

  def getMetricInfo(name: String): Future[Option[MetricDescriptor]] = {
    metricDescriptorRepo.getByName(name)
  }

  def getMetric(name: String): Future[Seq[MetricData]] = {
    metricRepoFactory(name).all.map{ dataPoints =>
      dataPoints.sortBy( md => md.dt.getMillis )
    }
  }

}
