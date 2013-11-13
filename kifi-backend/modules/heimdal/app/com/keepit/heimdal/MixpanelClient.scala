package com.keepit.heimdal
import com.keepit.serializer.TypeCode
import play.api.libs.json._
import org.apache.commons.codec.binary.Base64
import play.api.libs.ws.WS


class MixpanelClient(projectToken: String) {
  def send[E <: HeimdalEvent: TypeCode](event: E) = {
    val eventCode = implicitly[TypeCode[E]]
    val eventName = JsString(s"${eventCode}_${event.eventType.name}")
    var properties = Json.toJson(event.context).as[JsObject] + ("token", JsString(projectToken)) + ("time", JsNumber(event.time.getMillis))
    properties.value.get("remoteAddress").foreach { ip => properties = properties + ("ip", ip) - "remoteAddress" }
    event match {
      case e: UserEvent => properties = properties + ("distinct_id", JsString(s"${eventCode}_${e.userId.toString}"))
      case _ =>
    }
    val data = Base64.encodeBase64String(Json.stringify(Json.obj("event" -> eventName, "properties" -> properties)).getBytes)
    WS.url("http://api.mixpanel.com/track").withQueryString(("data", data)).get()
  }
}
