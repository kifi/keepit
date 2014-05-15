package com.keepit.model

import play.api.libs.json._
import com.keepit.common.mail.ElectronicMailCategory

case class NotificationCategory(category: String)
object NotificationCategory {
  val ALL = NotificationCategory("all")
  object User {
    val ANNOUNCEMENT = NotificationCategory("announcement")
    val WAITLIST = NotificationCategory("waitlist")
    val APPROVED = NotificationCategory("approved")
    val WELCOME = NotificationCategory("welcome")
    val EMAIL_CONFIRMATION = NotificationCategory("email_confirmation")
    val RESET_PASSWORD = NotificationCategory("reset_password")
    val EMAIL_KEEP = NotificationCategory("email_keep")
    val WHO_KEPT_MY_KEEP = NotificationCategory("who_kept_my_keep")

    val INVITATION = NotificationCategory("invitation")
    val MESSAGE = NotificationCategory("message")
    val FRIEND_REQUEST = NotificationCategory("friend_request")
    val FRIEND_ACCEPTED = NotificationCategory("friend_accepted")

    val FRIEND_JOINED = NotificationCategory("friend_joined")

    val all = Set(ANNOUNCEMENT, MESSAGE, EMAIL_KEEP, INVITATION, EMAIL_CONFIRMATION, RESET_PASSWORD, FRIEND_REQUEST, FRIEND_ACCEPTED, FRIEND_JOINED, WELCOME, APPROVED, WAITLIST, WHO_KEPT_MY_KEEP)

    // Parent Categories used in analytics
    val fromKifi = Set(ANNOUNCEMENT, WAITLIST, APPROVED, WELCOME, EMAIL_CONFIRMATION, RESET_PASSWORD, EMAIL_KEEP, WHO_KEPT_MY_KEEP)
    val fromFriends = Set(INVITATION, MESSAGE, FRIEND_REQUEST, FRIEND_ACCEPTED)
    val aboutFriends = Set(FRIEND_JOINED)
    val parentCategory: Map[NotificationCategory, String] =
      Map.empty ++ fromKifi.map(_ -> "fromKifi") ++ fromFriends.map(_ -> "fromFriends") ++ aboutFriends.map(_ -> "aboutFriends")

    // Formatting Categories used in the extension
    val triggered = Set(FRIEND_ACCEPTED, FRIEND_JOINED, FRIEND_REQUEST, WHO_KEPT_MY_KEEP)
    val global = Set(ANNOUNCEMENT)
    val kifiMessageFormattingCategory = Map.empty ++ triggered.map(_ -> "triggered") ++ global.map(_ -> "global")
  }

  object System {
    val HEALTHCHECK = NotificationCategory("healthcheck")
    val ADMIN = NotificationCategory("admin")
    val SCRAPER = NotificationCategory("scraper")
    val PLAY = NotificationCategory("play")
    val all = Set(HEALTHCHECK, ADMIN, PLAY, SCRAPER)
  }

  object NonUser {
    val INVITATION = NotificationCategory("invitation")
    val DISCUSSION_STARTED = NotificationCategory("discussion_started")
    val ADDED_TO_DISCUSSION = NotificationCategory("added_to_discussion")
    val DISCUSSION_UPDATES = NotificationCategory("discussion_updates")

    val all = Set(INVITATION, DISCUSSION_STARTED, DISCUSSION_UPDATES, ADDED_TO_DISCUSSION)
  }

  implicit def toElectronicMailCategory(category: NotificationCategory): ElectronicMailCategory = ElectronicMailCategory(category.category)
  implicit def fromElectronicMailCategory(category: ElectronicMailCategory): NotificationCategory = NotificationCategory(category.category)
  implicit val format: Format[NotificationCategory] = Format(
    __.read[String].map(s => NotificationCategory(s)),
    new Writes[NotificationCategory]{ def writes(o: NotificationCategory) = JsString(o.category) }
  )
}