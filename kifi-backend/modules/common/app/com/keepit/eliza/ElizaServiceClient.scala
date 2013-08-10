package com.keepit.eliza


import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.logging.Logging
import com.keepit.common.routes.Eliza
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceCluster

import play.api.libs.json.{JsArray, Json, JsObject}

import com.google.inject.Inject

trait ElizaServiceClient extends ServiceClient {
  final val serviceType = ServiceType.ELIZA
  def sendToUserNoBroadcast(userId: Id[User], data: JsArray): Unit
  def sendToUser(userId: Id[User], data: JsArray): Unit

  //migration
  def importThread(data: JsObject): Unit
}


class ElizaServiceClientImpl @Inject() (
    val healthcheck: HealthcheckPlugin,
    val httpClient: HttpClient,
    val serviceCluster: ServiceCluster
  ) 
  extends ElizaServiceClient with Logging {

  def sendToUserNoBroadcast(userId: Id[User], data: JsArray): Unit = {
    implicit val userFormatter = Id.format[User]
    val payload = Json.obj("userId" -> userId, "data" -> data)
    broadcast(Eliza.internal.sendToUserNoBroadcast, payload)
  }

  def sendToUser(userId: Id[User], data: JsArray): Unit = {
    implicit val userFormatter = Id.format[User]
    val payload = Json.obj("userId" -> userId, "data" -> data)
    call(Eliza.internal.sendToUser, payload)
  }

  //migration
  def importThread(data: JsObject): Unit = {
    call(Eliza.internal.importThread, data)
  }

}

class FakeElizaServiceClientImpl(val healthcheck: HealthcheckPlugin) extends ElizaServiceClient{
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE)
  protected def httpClient: com.keepit.common.net.HttpClient = ???
  
  def sendToUserNoBroadcast(userId: Id[User], data: JsArray): Unit = {}

  def sendToUser(userId: Id[User], data: JsArray): Unit = {}

  //migration
  def importThread(data: JsObject): Unit = {}


}
