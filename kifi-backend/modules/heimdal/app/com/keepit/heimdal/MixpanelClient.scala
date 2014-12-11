package com.keepit.heimdal

import play.api.libs.json._
import org.apache.commons.codec.binary.Base64
import play.api.libs.ws.WS
import com.keepit.model.{ Gender, User }
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.db.{ ExternalId, Id }
import play.api.Play.current

import scala.concurrent.Future

class MixpanelClient(projectToken: String, shoebox: ShoeboxServiceClient) {

  def track[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): Future[Unit] = {
    val eventName = s"${companion.typeCode}_${event.eventType.name}"
    val properties = new HeimdalContextBuilder()
    properties.data ++= event.context.data
    if (!properties.data.contains("ip")) { properties.data += ("ip" -> event.context.data.getOrElse("remoteAddress", ContextDoubleData(0))) }
    properties += ("distinct_id", getDistinctId(event))
    properties += ("time", event.time.getMillis / 1000)
    if (!properties.data.contains("token")) { properties += ("token", projectToken) }
    val data = Json.obj("event" -> JsString(eventName), "properties" -> Json.toJson(properties.build))
    sendData("http://api.mixpanel.com/track", data) map (_ => ())
  }

  private def getDistinctId(id: Id[User]): String = s"${UserEvent.typeCode}_${id.toString}"
  private def getDistinctId[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): String = event match {
    case userEvent: UserEvent => getDistinctId(userEvent.userId)
    case nonUserEvent: NonUserEvent => nonUserEvent.identifier
    case otherEvent => otherEvent.context.get[String]("distinct_id") getOrElse companion.typeCode
  }

  def incrementUserProperties(userId: Id[User], increments: Map[String, Double]) = {
    val data = Json.obj(
      "$token" -> JsString(projectToken),
      "$distinct_id" -> JsString(getDistinctId(userId)),
      "$ip" -> JsNumber(0),
      "$add" -> JsObject(increments.mapValues(JsNumber(_)).toSeq)
    )
    sendData("http://api.mixpanel.com/engage", data)
  }

  def setUserProperties(userId: Id[User], properties: HeimdalContext) = {
    val data = Json.obj(
      "$token" -> JsString(projectToken),
      "$distinct_id" -> JsString(getDistinctId(userId)),
      "$ip" -> JsNumber(0),
      "$set" -> Json.toJson(properties)
    )
    sendData("http://api.mixpanel.com/engage", data)
  }

  def delete(userId: Id[User]) = {
    val data = Json.obj(
      "$token" -> JsString(projectToken),
      "$distinct_id" -> JsString(getDistinctId(userId)),
      "$delete" -> JsString("")
    )
    sendData("http://api.mixpanel.com/engage", data)
    sendData("http://api.mixpanel.com/engage", data + ("$ignore_alias" -> JsBoolean(true)))
  }

  def alias(userId: Id[User], externalId: ExternalId[User]) = {
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

  private def sendData(url: String, data: JsObject) = {
    val request = WS.url(url).withQueryString(("data", Base64.encodeBase64String(Json.stringify(data).getBytes)))
    new SafeFuture(
      request.get().map {
        case response if response.body == "0\n" => throw new Exception(s"Mixpanel endpoint $url refused data: $data")
        case response => response
      }, Some("Mixpanel Event Forwarding"))
  }
}
