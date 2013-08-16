package com.keepit.eliza


import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.logging.Logging
import com.keepit.common.routes.Eliza
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceCluster

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

import play.api.libs.json.{JsArray, Json, JsObject}

import com.google.inject.Inject

trait ElizaServiceClient extends ServiceClient {
  final val serviceType = ServiceType.ELIZA
  def sendToUserNoBroadcast(userId: Id[User], data: JsArray): Unit
  def sendToUser(userId: Id[User], data: JsArray): Unit
  def sendToAllUsers(data: JsArray): Unit

  def connectedClientCount: Future[Seq[Int]]

  def sendGlobalNotification(userIds: Set[Id[User]], title: String, body: String, linkText: String, linkUrl: String, imageUrl: String, sticky: Boolean) : Unit

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

  def sendToAllUsers(data: JsArray): Unit = {
    broadcast(Eliza.internal.sendToAllUsers, data)
  }

  def connectedClientCount: Future[Seq[Int]] = {
    Future.sequence(broadcast(Eliza.internal.connectedClientCount)).map{ respSeq =>
      respSeq.map{ resp => resp.body.toInt }
    }
  }

  def sendGlobalNotification(userIds: Set[Id[User]], title: String, body: String, linkText: String, linkUrl: String, imageUrl: String, sticky: Boolean) : Unit = {
    implicit val userFormatter = Id.format[User]
    val payload = Json.obj(
      "userIds"   -> userIds.toSeq,
      "title"     -> title,
      "body"      -> body,
      "linkText"  -> linkText,
      "linkUrl"   -> linkUrl,
      "imageUrl"  -> imageUrl,
      "sticky"    -> sticky
    )
    call(Eliza.internal.sendGlobalNotification, payload)
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

  def sendToAllUsers(data: JsArray): Unit = {}

  def connectedClientCount: Future[Seq[Int]] = {
    val p = Promise.successful(Seq[Int](1))
    p.future
  }

  def sendGlobalNotification(userIds: Set[Id[User]], title: String, body: String, linkText: String, linkUrl: String, imageUrl: String, sticky: Boolean) : Unit = {}

  //migration
  def importThread(data: JsObject): Unit = {}


}
