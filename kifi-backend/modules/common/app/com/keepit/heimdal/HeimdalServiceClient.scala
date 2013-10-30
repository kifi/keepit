package com.keepit.heimdal

import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.logging.Logging
import com.keepit.common.routes.Heimdal
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery

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

class HeimdalClientActor @Inject() (
    val airbrakeNotifier: AirbrakeNotifier,
    val httpClient: HttpClient,
    serviceDiscovery: ServiceDiscovery
  ) extends FortyTwoActor(airbrakeNotifier) with ServiceClient with Logging {

  final val serviceType = ServiceType.HEIMDAL
  val serviceCluster = serviceDiscovery.serviceCluster(serviceType)

  def receive = {
    case event: UserEvent => call(Heimdal.internal.trackEvent, Json.toJson(event))
    case m => throw new UnsupportedActorMessage(m)
  }
}

class HeimdalServiceClientImpl @Inject() (
    val airbrakeNotifier: AirbrakeNotifier,
    val httpClient: HttpClient,
    val serviceCluster: ServiceCluster,
    actor: ActorInstance[HeimdalClientActor])
  extends HeimdalServiceClient with Logging {

  def trackEvent(event: UserEvent) : Unit = actor.ref ! event

  def getMetricData(name: String): Future[JsObject] = {
    call(Heimdal.internal.getMetricData(name)).map{ response =>
      Json.parse(response.body).as[JsObject]
    }
  }

  def updateMetrics(): Unit = {
    broadcast(Heimdal.internal.updateMetrics())
  }
}


