package com.keepit.heimdal

import com.keepit.model.{ UserEventLoggingRepo }
import org.joda.time.DateTime

import com.keepit.common.time._
import com.keepit.common.zookeeper.ServiceDiscovery

import play.api.libs.json.{ JsArray, Json }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.duration._
import scala.concurrent.{ Future, Await }

import com.google.inject.Inject

case class MetricDescriptor(name: String, start: DateTime, window: Int, step: Int, description: String, events: Seq[String], groupBy: String, breakDown: Boolean, mode: String, filters: Seq[String], lastUpdate: DateTime, uniqueField: String)

object MetricDescriptor {
  implicit val format = Json.format[MetricDescriptor]
}

class MetricManager @Inject() (
    userEventLoggingRepo: UserEventLoggingRepo,
    serviceDiscovery: ServiceDiscovery) {

  var updateInProgress: Boolean = false

  def createMetric(descriptor: MetricDescriptor): Unit = {}

  def updateMetricFully(descriptor: MetricDescriptor): Unit = {}

  def updateAllMetrics(): Unit = {}

  def getAvailableMetrics: Future[Seq[MetricDescriptor]] = Future.successful(Seq.empty)

  def getMetricInfo(name: String): Future[Option[MetricDescriptor]] = Future.successful(None)

}
