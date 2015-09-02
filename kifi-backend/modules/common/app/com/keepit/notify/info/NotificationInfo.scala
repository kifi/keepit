package com.keepit.notify.info

import com.keepit.model.NotificationCategory
import com.keepit.social.BasicUser
import play.api.libs.json.JsObject
import play.api.libs.json._
import play.api.libs.functional.syntax._

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
case class NotificationInfo(
  url: String,
  image: NotificationImage,
  title: String,
  body: String,
  linkText: String,
  category: Option[NotificationCategory] = None,
  extraJson: Option[JsObject] = None)

object NotificationInfo {

  def apply(
    user: BasicUser,
    title: String,
    body: String,
    linkText: String,
    category: Option[NotificationCategory] = None,
    extraJson: Option[JsObject] = None): NotificationInfo = {
    NotificationInfo(
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

sealed trait NotificationImage

case class UserImage(basicUser: BasicUser) extends NotificationImage

case class PublicImage(url: String) extends NotificationImage
