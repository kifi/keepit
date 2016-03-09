package com.keepit.heimdal

import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.logging.Logging
import com.keepit.model.User
import org.apache.commons.codec.binary.Base64
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.libs.ws.WS

import scala.concurrent.Future

trait MixpanelClient {
  def track[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): Future[Unit]
  def incrementUserProperties(userId: Id[User], increments: Map[String, Double]): Future[Unit]
  def setUserProperties(userId: Id[User], properties: HeimdalContext): Future[Unit]
  def delete(userId: Id[User]): Future[Unit]
  def alias(userId: Id[User], externalId: ExternalId[User]): Future[Unit]
}

class MixpanelClientImpl(projectToken: String) extends MixpanelClient with Logging {

  def track[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): Future[Unit] = {
    val eventName = s"${companion.typeCode}_${event.eventType.name}"
    val properties = new HeimdalContextBuilder()
    properties.data ++= event.context.data
    if (!properties.data.contains("ip")) { properties.data += ("ip" -> ContextStringData(getIpAddress(event) getOrElse "0")) }
    properties += ("distinct_id", getDistinctId(event))
    properties += ("time", event.time.getMillis / 1000)
    if (!properties.data.contains("token")) { properties += ("token", projectToken) }
    val data = Json.obj("event" -> JsString(eventName), "properties" -> Json.toJson(properties.build))
    if (event.eventType == UserEventTypes.SEARCHED) log.info(s"[searchedUserStatus] sending searched event with data ${Json.stringify(data)}")
    sendData("http://api.mixpanel.com/track", data) map (_ => ())
  }

  private def getIpAddress(event: HeimdalEvent): Option[String] = event.context.get[String]("ip") orElse event.context.get[String]("remoteAddress")
  private def getDistinctId(id: Id[User]): String = s"${UserEvent.typeCode}_${id.toString}"
  private def getDistinctId[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): String = event match {
    case userEvent: UserEvent => getDistinctId(userEvent.userId)
    case nonUserEvent: NonUserEvent => nonUserEvent.identifier
    case visitorEvent: VisitorEvent => visitorEvent.context.get[String]("distinct_id") orElse getIpAddress(visitorEvent) getOrElse VisitorEvent.typeCode
    case systemEvent: SystemEvent => SystemEvent.typeCode
    case anonymousEvent: AnonymousEvent => AnonymousEvent.typeCode
  }

  def incrementUserProperties(userId: Id[User], increments: Map[String, Double]): Future[Unit] = {
    val data = Json.obj(
      "$token" -> JsString(projectToken),
      "$distinct_id" -> JsString(getDistinctId(userId)),
      "$ip" -> JsNumber(0),
      "$add" -> JsObject(increments.mapValues(JsNumber(_)).toSeq)
    )
    sendData("http://api.mixpanel.com/engage", data)
  }

  def setUserProperties(userId: Id[User], properties: HeimdalContext): Future[Unit] = {
    val data = Json.obj(
      "$token" -> JsString(projectToken),
      "$distinct_id" -> JsString(getDistinctId(userId)),
      "$ip" -> JsNumber(0),
      "$set" -> Json.toJson(properties)
    )
    sendData("http://api.mixpanel.com/engage", data)
  }

  def delete(userId: Id[User]): Future[Unit] = {
    val data = Json.obj(
      "$token" -> JsString(projectToken),
      "$distinct_id" -> JsString(getDistinctId(userId)),
      "$delete" -> JsString("")
    )
    sendData("http://api.mixpanel.com/engage", data)
    sendData("http://api.mixpanel.com/engage", data + ("$ignore_alias" -> JsBoolean(true)))
  }

  def alias(userId: Id[User], externalId: ExternalId[User]): Future[Unit] = {
    val data = Json.obj(
      "event" -> "$create_alias",
      "properties" -> Json.obj(
        "token" -> JsString(projectToken),
        "distinct_id" -> externalId.id,
        "alias" -> JsString(getDistinctId(userId))
      )
    )
    sendData("http://api.mixpanel.com/track", data)
  }

  private def sendData(url: String, data: JsObject): Future[Unit] = {
    val request = WS.url(url).withQueryString(("data", Base64.encodeBase64String(Json.stringify(data).getBytes)))
    new SafeFuture(
      request.get().map {
        case response if response.body == "0\n" => throw new Exception(s"Mixpanel endpoint $url refused data: $data")
        case response => response
      }, Some("Mixpanel Event Forwarding")).map(_ => ())
  }
}
