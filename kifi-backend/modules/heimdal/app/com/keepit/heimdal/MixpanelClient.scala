package com.keepit.heimdal

import com.keepit.serializer.TypeCode
import play.api.libs.json._
import org.apache.commons.codec.binary.Base64
import play.api.libs.ws.WS

class MixpanelClient(projectToken: String) {

  def send[E <: HeimdalEvent: TypeCode](event: E) = {
    val eventCode = implicitly[TypeCode[E]]
    val eventName = s"${eventCode}_${event.eventType.name}"
    val distinctId = event match {
      case e: UserEvent => s"${eventCode}_${e.userId.toString}"
      case _ => eventCode.toString
    }
    val properties = getProperties(event.context) :+ ("distinct_id" -> JsString(distinctId)) :+ ("token", JsString(projectToken)) :+ ("time", JsNumber(event.time.getMillis))

    val data = Base64.encodeBase64String(Json.stringify(Json.obj("event" -> JsString(eventName), "properties" -> JsObject(properties))).getBytes)
    WS.url("http://api.mixpanel.com/track").withQueryString(("data", data)).get()
  }

  private def getProperties(context: EventContext) : Seq[(String, JsValue)] = context.data.map {
      case ("remoteAddress", Seq(ContextStringData(ip))) => "ip" -> JsString(ip)
      case (key, Seq(ContextStringData(s))) => key -> JsString(s)
      case (key, Seq(ContextDoubleData(x))) => key -> JsNumber(x)
      case (key, seq) => key -> JsArray(seq.map{ _ match {
        case ContextStringData(s) => JsString(s)
        case ContextDoubleData(x) => JsNumber(x)
      }})
  }.toSeq
}