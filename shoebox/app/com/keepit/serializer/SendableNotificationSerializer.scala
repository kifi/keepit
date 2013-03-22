package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model._
import play.api.libs.json._
import com.keepit.common.social.UserWithSocial
import com.keepit.common.db._
import com.keepit.realtime.SendableNotification

class SendableNotificationSerializer extends Format[SendableNotification] {

  def writes(notify: SendableNotification): JsValue =
    JsObject(List(
      "createdAt" -> JsString(notify.createdAt.toStandardTimeString),
      "updatedAt" -> JsString(notify.updatedAt.toStandardTimeString),
      "id" -> JsString(notify.id.id),
      "category"  -> JsString(notify.category.name),
      "details"  -> notify.details.payload
    ))

  def reads(json: JsValue): JsResult[SendableNotification] =
    JsSuccess(SendableNotification(
      id = ExternalId[UserNotification]((json \ "id").as[String]),
      createdAt = parseStandardTime((json \ "createdAt").as[String]),
      updatedAt = parseStandardTime((json \ "updatedAt").as[String]),
      category = UserNotificationCategory((json \ "firstName").as[String]),
      details = UserNotificationDetails((json \ "details").as[JsObject])
    ))
}

object SendableNotificationSerializer {
  implicit val sendableNotificationSerializer = new SendableNotificationSerializer
}
