package com.keepit.notify.info

import com.keepit.model.{ User, NotificationCategory }
import com.keepit.notify.model.event.NewMessage
import com.keepit.social.BasicUser
import play.api.libs.json.JsObject
import play.api.libs.json._
import play.api.libs.functional.syntax._

sealed trait NotificationInfo

/**
 * Represents what is ultimately displayed to the user in the form of an actionable notification.
 *
 * @param url The url the user goes to
 * @param image The image to display
 * @param title The title of the notification
 * @param body The body of the notification, text explaining why it happened or the relation to the user
 * @param linkText Text that goes on link hover, explains where the link goes
 * @param category Provisional 'category' of the notification for use in clients
 * @param extraJson Assorted data that is read by clients, per-notification
 */
case class StandardNotificationInfo(
  url: String,
  image: NotificationImage,
  title: String,
  body: String,
  linkText: String,
  category: NotificationCategory,
  extraJson: Option[JsObject] = None) extends NotificationInfo

object StandardNotificationInfo {

  def apply(
    user: BasicUser,
    title: String,
    body: String,
    linkText: String,
    category: NotificationCategory,
    extraJson: Option[JsObject]): StandardNotificationInfo = {
    StandardNotificationInfo(
      user.path.encode.absolute,
      UserImage(user),
      title,
      body,
      linkText,
      category,
      extraJson
    )
  }

}

case class LegacyNotificationInfo(json: JsValue) extends NotificationInfo

case class MessageNotificationInfo(newMessages: Set[NewMessage]) extends NotificationInfo

sealed trait NotificationImage

case class UserImage(user: BasicUser) extends NotificationImage

case class PublicImage(url: String) extends NotificationImage
