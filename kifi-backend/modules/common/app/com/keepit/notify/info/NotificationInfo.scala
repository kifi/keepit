package com.keepit.notify.info

import com.keepit.model.{ DeepLocator, BasicOrganization, User, NotificationCategory }
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
 * @param locator Optional locator used to trigger a specific behavior in the extension
 * @param extraJson Assorted data that is read by clients, per-notification
 */
case class StandardNotificationInfo(
  url: String,
  image: NotificationImage,
  title: String,
  body: String,
  linkText: String,
  category: NotificationCategory,
  locator: Option[DeepLocator] = None,
  extraJson: Option[JsObject] = None) extends NotificationInfo

object StandardNotificationInfo {

  def apply(
    user: BasicUser,
    title: String,
    body: String,
    linkText: String,
    category: NotificationCategory,
    locator: Option[DeepLocator],
    extraJson: Option[JsObject]): StandardNotificationInfo = {
    StandardNotificationInfo(
      user.path.encode.absolute,
      UserImage(user),
      title,
      body,
      linkText,
      category,
      locator,
      extraJson
    )
  }

}

sealed trait NotificationImage

case class UserImage(user: BasicUser) extends NotificationImage
case class OrganizationImage(organization: BasicOrganization) extends NotificationImage

case class PublicImage(url: String) extends NotificationImage
