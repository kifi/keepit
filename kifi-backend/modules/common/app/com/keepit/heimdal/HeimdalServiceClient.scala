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

trait HeimdalServiceClient extends ServiceClient {
  final val serviceType = ServiceType.HEIMDAL

  def trackEvent(event: UserEvent): Unit

  def getMetricData(name: String): Future[JsObject]

  def updateMetrics(): Unit
}

class HeimdalServiceClientImpl @Inject() (
    val airbrakeNotifier: AirbrakeNotifier,
    val httpClient: HttpClient,
    val serviceCluster: ServiceCluster
  )
  extends HeimdalServiceClient with Logging {

  def trackEvent(event: UserEvent) : Unit = {
    call(Heimdal.internal.trackEvent, Json.toJson(event))
  }

  def getMetricData(name: String): Future[JsObject] = {
    call(Heimdal.internal.getMetricData(name)).map{ response =>
      Json.parse(response.body).as[JsObject]
    }
  }

  def updateMetrics(): Unit = {
    broadcast(Heimdal.internal.updateMetrics())
  }
}


