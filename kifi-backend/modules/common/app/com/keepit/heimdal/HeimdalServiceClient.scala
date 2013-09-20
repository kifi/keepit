package com.keepit.heimdal


import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.logging.Logging
import com.keepit.common.routes.Heimdal
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceCluster

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

import play.api.libs.json.{JsArray, Json, JsObject}

import com.google.inject.Inject

trait HeimdalServiceClient extends ServiceClient {
  final val serviceType = ServiceType.HEIMDAL

  def trackEvent(event: UserEvent): Unit
}


class HeimdalServiceClientImpl @Inject() (
    val healthcheck: HealthcheckPlugin,
    val httpClient: HttpClient,
    val serviceCluster: ServiceCluster
  ) 
  extends HeimdalServiceClient with Logging {

  def trackEvent(event: UserEvent) : Unit = {
    call(Heimdal.internal.trackEvent, Json.toJson(event))
  }

}

class FakeHeimdalServiceClientImpl(val healthcheck: HealthcheckPlugin) extends HeimdalServiceClient{
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE)
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def trackEvent(event: UserEvent) : Unit = {}

}
