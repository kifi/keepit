package com.keepit.serializer

import com.keepit.common.time._
import com.keepit.model._
import play.api.libs.json._
import com.keepit.common.db._
import com.keepit.realtime.SendableNotification
import org.joda.time.DateTime

class SendableNotificationSerializer extends Format[SendableNotification] {

  def writes(notify: SendableNotification): JsValue =
    Json.obj(
      "time" -> Json.toJson(notify.time),
      "id" -> JsString(notify.id.id),
      "category"  -> JsString(notify.category.name),
      "details"  -> notify.details.payload,
      "state" -> notify.state.value
    )

  def reads(json: JsValue): JsResult[SendableNotification] =
    JsSuccess(SendableNotification(
      id = ExternalId[UserNotification]((json \ "id").as[String]),
      time = (json \ "time").as[DateTime],
      category = UserNotificationCategory((json \ "firstName").as[String]),
      details = UserNotificationDetails((json \ "details").as[JsObject]),
      state = State[UserNotification]((json \ "state").as[String])
    ))
}

object SendableNotificationSerializer {
  implicit val sendableNotificationSerializer = new SendableNotificationSerializer
}
