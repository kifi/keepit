package com.keepit.heimdal

import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.logging.Logging
import com.keepit.common.routes.Heimdal
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceCluster

import scala.concurrent.{Future, Promise}

import play.api.libs.json.{JsArray, Json, JsObject}
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject
import com.keepit.serializer.Companion


class FakeHeimdalServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends HeimdalServiceClient{
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE)
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  var eventsRecorded : Int = 0

  def trackEvent(event: HeimdalEvent): Unit = synchronized{
    eventsRecorded =  eventsRecorded + 1
  }

  def eventCount: Int = eventsRecorded

  def getMetricData[E <: HeimdalEvent: Companion](name: String): Future[JsObject] = Promise.successful(Json.obj()).future

  def updateMetrics(): Unit = {}

  def getRawEvents[E <: HeimdalEvent: Companion](limit: Int, events: EventType*): Future[JsArray] = Future.successful(Json.arr())
}
