package com.keepit.heimdal

import com.keepit.serializer.TypeCode
import play.api.libs.json._
import org.apache.commons.codec.binary.Base64
import play.api.libs.ws.WS
import com.keepit.model.{Gender, User}
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.db.Id
import scala.concurrent.Future

class MixpanelClient(projectToken: String, shoebox: ShoeboxServiceClient) {

  def track[E <: HeimdalEvent: TypeCode](event: E) = {
    val eventCode = implicitly[TypeCode[E]]
    val eventName = s"${eventCode}_${event.eventType.name}"
    val userId = Some(event) collect { case userEvent: UserEvent => Id[User](userEvent.userId) }

    val distinctId = userId.map(getDistinctId) getOrElse eventCode.toString
    val superPropertiesFuture = userId.map(getSuperProperties) getOrElse Future.successful(Seq.empty)

    val properties = new EventContextBuilder()
    properties.data ++= event.context.data
    properties.data += ("ip" -> event.context.data.getOrElse("remoteAddress", ContextDoubleData(0)))
    userId.foreach(id => properties += ("userId", id.id))
    properties += ("distinct_id", distinctId)
    properties += ("token", projectToken)
    properties += ("time", event.time.getMillis)

    for (superProperties <- superPropertiesFuture recover { case _ : Throwable => Seq.empty } ) yield {
      properties.data ++= superProperties
      val data = Json.obj("event" -> JsString(eventName), "properties" -> Json.toJson(properties.build))
      sendData("http://api.mixpanel.com/track", data)
    }
  }

  private def getSuperProperties(userId: Id[User]): Future[Seq[(String, ContextData)]] =
    shoebox.getUserValue(userId, Gender.key).map(_.map { gender =>
      Seq(Gender.key -> ContextStringData(Gender(gender).toString))
    } getOrElse(Seq.empty))

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
  }


  private def sendData(url: String, data: JsObject) = {
    val request = WS.url(url).withQueryString(("data", Base64.encodeBase64String(Json.stringify(data).getBytes)))
    new SafeFuture(
      request.get().map {
        case response if response.body == "0\n" => throw new Exception(s"Mixpanel endpoint $url refused data: $data")
        case response => response
      }
    )
  }

  private def getDistinctId(id: Id[User]) = s"${UserEvent.typeCode}_${id.toString}"

}