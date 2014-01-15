package com.keepit.model

import com.keepit.common.mail.ElectronicMailCategory

case class NotificationCategory(category: String)
object NotificationCategory {
  val ALL = NotificationCategory("all")
  object User {
    val GLOBAL = NotificationCategory("global")
    val WAITLIST = NotificationCategory("waitlist")
    val APPROVED = NotificationCategory("approved")
    val WELCOME = NotificationCategory("welcome")
    val EMAIL_CONFIRMATION = NotificationCategory("email_confirmation")
    val RESET_PASSWORD = NotificationCategory("reset_password")
    val EMAIL_KEEP = NotificationCategory("email_keep")

    val INVITATION = NotificationCategory("invitation")
    val MESSAGE = NotificationCategory("message")
    val FRIEND_REQUEST = NotificationCategory("friend_request")
    val FRIEND_ACCEPTED = NotificationCategory("friend_accepted")

    val FRIEND_JOINED = NotificationCategory("friend_joined")

    val all = Set(GLOBAL, MESSAGE, EMAIL_KEEP, INVITATION, EMAIL_CONFIRMATION, RESET_PASSWORD, FRIEND_REQUEST, FRIEND_ACCEPTED, FRIEND_JOINED, WELCOME, APPROVED, WAITLIST)
    val fromKifi = Set(GLOBAL, WAITLIST, APPROVED, WELCOME, EMAIL_CONFIRMATION, RESET_PASSWORD, EMAIL_KEEP)
    val fromFriends = Set(INVITATION, MESSAGE, FRIEND_REQUEST, FRIEND_ACCEPTED)
    val aboutFriends = Set(FRIEND_JOINED)

    val parentCategory: Map[NotificationCategory, String] =
      Map.empty ++ fromKifi.map(_ -> "fromKifi") ++ fromFriends.map(_ -> "fromFriends") ++ aboutFriends.map(_ -> "aboutFriends")
  }

  object System {
    val HEALTHCHECK = NotificationCategory("healthcheck")
    val ASANA_HEALTHCHECK = NotificationCategory("asana_healthcheck")
    val ADMIN = NotificationCategory("admin")
    val PLAY = NotificationCategory("play")
    val all = Set(HEALTHCHECK, ASANA_HEALTHCHECK, ADMIN, PLAY)
  }

  implicit def toElectronicMailCategory(category: NotificationCategory): ElectronicMailCategory = ElectronicMailCategory(category.category)
  implicit def fromElectronicMailCategory(category: ElectronicMailCategory): NotificationCategory = NotificationCategory(category.category)
}