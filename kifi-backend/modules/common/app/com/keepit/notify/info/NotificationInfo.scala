package com.keepit.notify.info

import com.keepit.common.path.Path
import play.api.libs.json.JsObject
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class NotificationInfo(
  url: String,
  imageUrl: String,
  title: String,
  body: String,
  linkText: String,
  extraJson: Option[JsObject] = None)

object NotificationInfo {

  implicit val format = (
    (__ \ "url").format[String] and
    (__ \ "imageUrl").format[String] and
    (__ \ "title").format[String] and
    (__ \ "body").format[String] and
    (__ \ "linkText").format[String] and
    (__ \ "extraJson").formatNullable[JsObject]
  )(NotificationInfo.apply, unlift(NotificationInfo.unapply))

}
