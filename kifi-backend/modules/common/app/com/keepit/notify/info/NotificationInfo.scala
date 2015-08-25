package com.keepit.notify.info

import com.keepit.common.path.Path
import play.api.libs.json.JsObject
import play.api.libs.json._
import play.api.libs.functional.syntax._

/**
 * Represents what is ultimately displayed to the user in the form of an actionable notification.
 *
 * @param url The url the user goes to
 * @param imageUrl The image to display
 * @param title The title of the notification
 * @param body The body of the notification, text explaining why it happened or the relation to the user
 * @param linkText Text that goes on link hover, explains where the link goes
 * @param extraJson Assorted data that is read by clients, per-notification
 */
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
