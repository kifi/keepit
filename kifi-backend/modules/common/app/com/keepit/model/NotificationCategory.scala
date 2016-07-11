package com.keepit.model

import play.api.libs.json._
import com.keepit.common.mail.ElectronicMailCategory

case class NotificationCategory(category: String)
object NotificationCategory {
  implicit val writes: Writes[NotificationCategory] = Writes { c => JsString(c.category) }
  val ALL = NotificationCategory("all")

  object ParentCategory {
    // Parent Categories used in analytics
    val fromKifi = User.fromKifi
    val fromFriends = User.fromFriends ++ NonUser.fromFriends
    val aboutFriends = User.aboutFriends
    val parentCategory: Map[NotificationCategory, String] = {
      Map.empty ++ fromKifi.map(_ -> "fromKifi") ++ fromFriends.map(_ -> "fromFriends") ++ aboutFriends.map(_ -> "aboutFriends")
    }

    def get(notificationCategory: NotificationCategory): Option[String] = parentCategory.get(notificationCategory)
  }

  object User {
    val ANNOUNCEMENT = NotificationCategory("announcement")
    val WAITLIST = NotificationCategory("waitlist")
    val APPROVED = NotificationCategory("approved")
    val WELCOME = NotificationCategory("welcome")
    val EMAIL_CONFIRMATION = NotificationCategory("email_confirmation")
    val RESET_PASSWORD = NotificationCategory("reset_password")
    val EMAIL_KEEP = NotificationCategory("email_keep")
    val WHO_KEPT_MY_KEEP = NotificationCategory("who_kept_my_keep")

    val MESSAGE = NotificationCategory("message")
    val FRIEND_REQUEST = NotificationCategory("friend_request")
    val FRIEND_ACCEPTED = NotificationCategory("friend_accepted")

    val CONTACT_JOINED = NotificationCategory("contact_joined")
    val CONNECTION_MADE = NotificationCategory("connection_made")
    val SOCIAL_FRIEND_JOINED = NotificationCategory("social_friend_joined")

    val LIBRARY_FOLLOWING = NotificationCategory("user_library_following")
    val LIBRARY_INVITATION = NotificationCategory("user_library_invitation")
    val LIBRARY_FOLLOWED = NotificationCategory("library_followed")
    val LIBRARY_COLLABORATED = NotificationCategory("library_collaborated")
    val NEW_KEEP = NotificationCategory("new_keep")
    val MANY_NEW_KEEPS = NotificationCategory("many_new_keeps")

    val ORGANIZATION_INVITATION = NotificationCategory("organization_invitation")
    val ORGANIZATION_JOINED = NotificationCategory("organization_joined")
    val REWARD_CREDIT_APPLIED = NotificationCategory("reward_credit_applied")
    val CREATE_TEAM = NotificationCategory("create_team")
    val JOIN_BY_VERIFYING = NotificationCategory("join_by_verifying")

    val DIGEST = NotificationCategory("digest")
    val DIGEST_QA = NotificationCategory("digest_qa")
    val ACTIVITY = NotificationCategory("activity")

    val EXPORT_READY = NotificationCategory("export_ready")

    val reportToAnalytics = Set(ANNOUNCEMENT, MESSAGE, EMAIL_KEEP, EMAIL_CONFIRMATION, RESET_PASSWORD, FRIEND_REQUEST,
      FRIEND_ACCEPTED, WELCOME, APPROVED, WAITLIST, WHO_KEPT_MY_KEEP, CONTACT_JOINED, CONNECTION_MADE,
      SOCIAL_FRIEND_JOINED, LIBRARY_FOLLOWING, LIBRARY_INVITATION, ORGANIZATION_INVITATION, DIGEST, ACTIVITY, CREATE_TEAM, JOIN_BY_VERIFYING)

    // Parent Categories used in analytics
    val fromKifi = Set(ANNOUNCEMENT, WAITLIST, APPROVED, WELCOME, EMAIL_CONFIRMATION, RESET_PASSWORD, EMAIL_KEEP,
      WHO_KEPT_MY_KEEP, DIGEST, ACTIVITY, CREATE_TEAM, JOIN_BY_VERIFYING, EXPORT_READY)
    val fromFriends = Set(MESSAGE, FRIEND_REQUEST, FRIEND_ACCEPTED, LIBRARY_INVITATION)
    val aboutFriends = Set(CONTACT_JOINED, CONNECTION_MADE, SOCIAL_FRIEND_JOINED)

    // Formatting Categories used in the extension
    val triggered = Set(FRIEND_ACCEPTED, FRIEND_REQUEST, WHO_KEPT_MY_KEEP, CONTACT_JOINED, CONNECTION_MADE, SOCIAL_FRIEND_JOINED, LIBRARY_INVITATION, LIBRARY_FOLLOWED, NEW_KEEP, MANY_NEW_KEEPS,
      ORGANIZATION_INVITATION, ORGANIZATION_JOINED)
    val global = Set(ANNOUNCEMENT)
    val kifiMessageFormattingCategory = Map.empty ++ triggered.map(_ -> "triggered") ++ global.map(_ -> "global")
  }

  object System {
    val HEALTHCHECK = NotificationCategory("healthcheck")
    val ADMIN = NotificationCategory("admin")
    val SCRAPER = NotificationCategory("scraper")
    val PLAY = NotificationCategory("play")
    val EMAIL_QA = NotificationCategory("email_qa")
    val LEADS = NotificationCategory("leads")
    val all = Set(HEALTHCHECK, ADMIN, PLAY, SCRAPER, EMAIL_QA, LEADS)
  }

  object NonUser {
    val INVITATION = NotificationCategory("invitation")
    val DISCUSSION_STARTED = NotificationCategory("discussion_started")
    val ADDED_TO_DISCUSSION = NotificationCategory("added_to_discussion")
    val DISCUSSION_UPDATES = NotificationCategory("discussion_updates")
    val LIBRARY_INVITATION = NotificationCategory("visitor_library_invitation")
    val ORGANIZATION_INVITATION = NotificationCategory("visitor_organization_invitation")
    val BILLING = NotificationCategory("billing")

    val INTEGRATION_WELCOME = NotificationCategory("integration_welcome")
    val INTEGRATOR_PRESYNC = NotificationCategory("integrator_presync")
    val INTEGRATOR_POSTSYNC = NotificationCategory("integrator_postsync")
    val TEAM_DIGEST = NotificationCategory("team_digest")
    val PERSONAL_DIGEST = NotificationCategory("personal_digest")
    val LIBRARY_DIGEST = NotificationCategory("library_digest")
    val NEW_KEEP = NotificationCategory("new_keep")
    val NEW_COMMENT = NotificationCategory("new_comment")
    val SETTINGS_TOGGLE = NotificationCategory("settings_toggle")
    val BOT_SETTINGS_UPGRADE = NotificationCategory("bot_settings_upgrade_dm")

    val reportToAnalytics = Set(INVITATION, DISCUSSION_STARTED, DISCUSSION_UPDATES, ADDED_TO_DISCUSSION, LIBRARY_INVITATION, ORGANIZATION_INVITATION, BILLING)

    // Formatting Categories used in the extension
    val fromFriends = Set(INVITATION, DISCUSSION_STARTED, ADDED_TO_DISCUSSION, DISCUSSION_UPDATES, LIBRARY_INVITATION)
  }

  implicit def toElectronicMailCategory(category: NotificationCategory): ElectronicMailCategory = ElectronicMailCategory(category.category)
  implicit def fromElectronicMailCategory(category: ElectronicMailCategory): NotificationCategory = NotificationCategory(category.category)
  implicit val format: Format[NotificationCategory] = Format(
    __.read[String].map(s => NotificationCategory(s)),
    new Writes[NotificationCategory] { def writes(o: NotificationCategory) = JsString(o.category) }
  )
}
