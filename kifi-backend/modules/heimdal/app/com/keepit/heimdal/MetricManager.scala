package com.keepit.heimdal

import com.keepit.model.{ MetricDescriptorRepo, UserEventLoggingRepo, MetricRepoFactory, MetricData }
import org.joda.time.DateTime

import com.keepit.common.time._
import com.keepit.common.zookeeper.ServiceDiscovery

import play.api.libs.json.{ JsArray, Json }
import play.modules.reactivemongo.json.ImplicitBSONHandlers._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.duration._
import scala.concurrent.{ Future, Await }

import reactivemongo.bson.BSONDocument
import reactivemongo.api.indexes.{ Index, IndexType }

import com.google.inject.Inject

case class MetricDescriptor(name: String, start: DateTime, window: Int, step: Int, description: String, events: Seq[String], groupBy: String, breakDown: Boolean, mode: String, filters: Seq[String], lastUpdate: DateTime, uniqueField: String)

object MetricDescriptor {
  implicit val format = Json.format[MetricDescriptor]
}

class MetricManager @Inject() (
    userEventLoggingRepo: UserEventLoggingRepo,
    metricDescriptorRepo: MetricDescriptorRepo,
    metricRepoFactory: MetricRepoFactory,
    serviceDiscovery: ServiceDiscovery) {

  var updateInProgress: Boolean = false

  val definedRestrictions = Map[String, ContextRestriction](
    "none" -> NoContextRestriction,
    "noadmins" -> AnyContextRestriction("context.experiments", NotEqualTo(ContextStringData("admin"))),
    "withkifiresults" -> AnyContextRestriction("context.kifiResults", GreaterThan(ContextDoubleData(0))),
    "clickedkifiresult" -> ConditionalContextRestriction(AnyContextRestriction("context.resultSource", EqualTo(ContextStringData("Kifi"))), UserEventTypes.CLICKED_SEARCH_RESULT),
    "nofakes" -> AnyContextRestriction("context.experiments", NotEqualTo(ContextStringData("fake"))), //Is this correct?
    "publickeepsonly_nofakes" -> AndContextRestriction(
      AnyContextRestriction("context.experiments", NotEqualTo(ContextStringData("fake"))),
      AnyContextRestriction("context.isPrivate", EqualTo(ContextBoolean(false)))
    ),
    "privatekeepsonly_nofakes" -> AndContextRestriction(
      AnyContextRestriction("context.experiments", NotEqualTo(ContextStringData("fake"))),
      AnyContextRestriction("context.isPrivate", EqualTo(ContextBoolean(true)))
    ),
    "newinstallsonly" -> AnyContextRestriction("context.firstTime", EqualTo(ContextBoolean(true)))
  )

  def computeAdHocMteric(startTime: DateTime, endTime: DateTime, definition: MetricDefinition): Future[JsArray] = {
    val (pipeline, postprocess) = definition.aggregationForTimeWindow(startTime, Duration(endTime.getMillis - startTime.getMillis, "ms"))
    userEventLoggingRepo.performAggregation(pipeline).map { bsonStream =>
      JsArray(postprocess(bsonStream).map { bson =>
        JsObjectReader.read(bson)
      })
    }
  }

  def createMetric(descriptor: MetricDescriptor): Unit = {
    metricDescriptorRepo.upsert(descriptor)
    metricRepoFactory.clear(descriptor.name)
  }

  def updateMetricOnce(desc: MetricDescriptor): MetricDescriptor = {
    val tStart = desc.lastUpdate.minusHours(desc.window).plusHours(desc.step)
    val tEnd = desc.lastUpdate.plusHours(desc.step)
    val eventsToConsider = if (desc.events.isEmpty) AllEvents else SpecificEventSet(desc.events.toSet.map((s: String) => EventType(s)))
    val contextRestriction = AndContextRestriction(desc.filters.map(definedRestrictions): _*)

    val definition = desc.mode match {
      case "users" => new GroupedUserCountMetricDefinition(eventsToConsider, contextRestriction, EventGrouping(desc.groupBy), if (desc.groupBy.startsWith("context")) desc.breakDown else false)
      case "count_unique" => new GroupedUniqueFieldCountMetricDefinition(eventsToConsider, contextRestriction, EventGrouping(desc.groupBy), desc.uniqueField, if (desc.groupBy.startsWith("context")) desc.breakDown else false)
      case "count" => new GroupedEventCountMetricDefinition(eventsToConsider, contextRestriction, EventGrouping(desc.groupBy), if (desc.groupBy.startsWith("context")) desc.breakDown else false)
      // case _ => new GroupedEventCountMetricDefinition(eventsToConsider, contextRestriction, EventGrouping(desc.groupBy), if (desc.groupBy.startsWith("context")) desc.breakDown else false)
    }

    val (pipeline, postprocess) = definition.aggregationForTimeWindow(tStart, Duration(tEnd.getMillis - tStart.getMillis, "ms"))
    val data: Seq[BSONDocument] = Await.result(userEventLoggingRepo.performAggregation(pipeline).map(postprocess(_)), 5 minutes)
    val metricData = MetricData(tEnd, data)
    val repo = metricRepoFactory(desc.name)
    repo.collection.indexesManager.ensure(Index(
      key = Seq(("time", IndexType.Ascending)),
      unique = true,
      dropDups = true
    ))
    repo.insert(metricData, dropDups = true)
    val newDesc = desc.copy(lastUpdate = tEnd)
    metricDescriptorRepo.upsert(desc)
    newDesc
  }

  def updateMetricFully(descriptor: MetricDescriptor): Unit = {
    val now = currentDateTime
    var desc = descriptor
    while (now.isAfter(desc.start.plusHours(desc.window)) && now.isAfter(desc.lastUpdate.plusHours(desc.step))) {
      try {
        desc = updateMetricOnce(desc)
      } catch {
        case x: Throwable => println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$"); println(x); println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$"); throw x;
      }
    }
  }

  def updateAllMetrics(): Unit = synchronized {
    if (serviceDiscovery.isLeader() && !updateInProgress) {
      updateInProgress = true
      val descriptorsFuture: Future[Seq[MetricDescriptor]] = metricDescriptorRepo.all
      descriptorsFuture.map { descriptors =>
        synchronized { descriptors.foreach(updateMetricFully(_)) }
        updateInProgress = false
      }
    }
  }

  def getAvailableMetrics: Future[Seq[MetricDescriptor]] = metricDescriptorRepo.all

  def getMetricInfo(name: String): Future[Option[MetricDescriptor]] = {
    metricDescriptorRepo.getByName(name)
  }

  def getMetric(name: String): Future[Seq[MetricData]] = {
    metricRepoFactory(name).allLean.map { dataPoints =>
      dataPoints.sortBy(md => md.dt.getMillis)
    }
  }

}
