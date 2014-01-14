package com.keepit.model

import com.keepit.common.mail.ElectronicMailCategory

case class NotificationCategory(category: String)
object NotificationCategory {

  val ALL = NotificationCategory("all")
  object User {


    val GLOBAL = NotificationCategory("global")
    val MESSAGE = NotificationCategory("message")
    val EMAIL_KEEP = NotificationCategory("email_keep")
    val INVITATION = NotificationCategory("invitation")
    val EMAIL_CONFIRMATION = NotificationCategory("email_confirmation")
    val RESET_PASSWORD = NotificationCategory("reset_password")
    val NOTIFICATION = NotificationCategory("notification")
    val all = Set(GLOBAL, MESSAGE, EMAIL_KEEP, INVITATION, EMAIL_CONFIRMATION, RESET_PASSWORD)
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