package com.keepit.model

import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, Key, StringCacheImpl }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.mvc.QueryStringBindable

import scala.concurrent.duration.Duration

case class UserValue(
    id: Option[Id[UserValue]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    name: UserValueName,
    value: String,
    state: State[UserValue] = UserValueStates.ACTIVE) extends ModelWithState[UserValue] {
  def withId(id: Id[UserValue]) = this.copy(id = Some(id))
  def withState(newState: State[UserValue]) = this.copy(state = newState)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

case class UserValueKey(userId: Id[User], key: UserValueName) extends Key[String] {
  override val version = 5
  val namespace = "uservalue"
  def toKey(): String = userId.id + "_" + key.name
}
class UserValueCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends StringCacheImpl[UserValueKey](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object UserValueStates extends States[UserValue]

@json case class UserValueName(name: String)

object UserValueName {
  val EXT_LOOK_HERE_MODE = UserValueName("ext_look_here_mode")
  val ENTER_TO_SEND = UserValueName("enter_to_send")
  val EXT_MAX_RESULTS = UserValueName("ext_max_results")
  val EXT_SHOW_EXT_MSG_INTRO = UserValueName("ext_show_ext_msg_intro")
  val EXT_SHOW_LIBRARY_INTRO = UserValueName("ext_show_library_intro")
  val EXT_SHOW_MOVE_INTRO = UserValueName("ext_show_move_intro")
  val HAS_SEEN_INSTALL = UserValueName("has_seen_install")
  val QUOTE_ANYWHERE_FTUE = UserValueName("quote_anywhere_ftue")
  val HIDE_SOCIAL_TOOLTIP = UserValueName("hide_social_tooltip")
  val WELCOME_EMAIL_SENT = UserValueName("welcomeEmailSent")
  val LAST_ACTIVE = UserValueName("last_active")
  val GENDER = UserValueName("gender")
  val USER_COLLECTION_ORDERING = UserValueName("user_collection_ordering")
  val USER_DESCRIPTION = UserValueName("user_description")
  val PENDING_PRIMARY_EMAIL = UserValueName("pending_primary_email")
  val FRIENDS_NOTIFIED_ABOUT_JOINING = UserValueName("friendsNotifiedAboutJoining") // no longer in use
  val CONTACTS_NOTIFIED_ABOUT_JOINING = UserValueName("contactsNotifiedAboutJoining")
  val UPDATED_USER_CONNECTIONS = UserValueName("updated_user_connections")
  val RECENT_INTERACTION = UserValueName("recent_interaction")
  val KIFI_CAMPAIGN_ID = UserValueName("kifi_campaign_id")
  val LAST_DIGEST_EMAIL_SCHEDULED_AT = UserValueName("last_digest_email_scheduled_at")
  val LATEST_EMAIL_TIPS_SENT = UserValueName("latest_email_tips")

  val TWITTER_FOLLOWERS_CURSOR = UserValueName("twitter_followers_cursor")
  val TWITTER_FRIENDS_CURSOR = UserValueName("twitter_friends_cursor")
  val TWITTER_LOOKUP_CURSOR = UserValueName("twitter_lookup_cursor")

  val TWITTER_SYNC_ACCEPT_SENT = UserValueName("twitter_sync_accept_sent")

  val TWITTER_FOLLOWERS_COUNT = UserValueName("twitter_followers_count")
  val TWITTER_DESCRIPTION = UserValueName("twitter_description")
  val TWITTER_BANNER_IMAGE = UserValueName("twitter_banner_image")
  val TWITTER_PROTECTED_ACCOUNT = UserValueName("twitter_protected_account")
  val TWITTER_STATUSES_COUNT = UserValueName("twitter_statuses_count")
  val TWITTER_FAVOURITES_COUNT = UserValueName("twitter_favourites_count")
  val SENT_TWITTER_SYNC_EMAIL = UserValueName("sent_twitter_sync_email")

  // ↓↓↓↓ This should be combined with preferences. Why does it exist?
  // User Profile Settings (be sure to add to UserValueSettings.defaultSettings for default values)
  val USER_PROFILE_SETTINGS = UserValueName("user_profile_settings")
  // ↑↑↑↑↑ Should be combined with preferences

  // Site user preferences
  val USE_MINIMAL_KEEP_CARD = UserValueName("use_minimal_keep_card")
  val AUTO_SHOW_GUIDE = UserValueName("auto_show_guide")
  val HAS_NO_PASSWORD = UserValueName("has_no_password")
  val SHOW_DELIGHTED_QUESTION = UserValueName("show_delighted_question")
  val HAS_SEEN_FTUE = UserValueName("has_seen_ftue")
  val STORED_CREDIT_CODE = UserValueName("stored_credit_code")
  val SLACK_INT_PROMO = UserValueName("slack_int_promo")
  val SLACK_UPSELL_WIDGET = UserValueName("slack_upsell_widget")
  val SHOW_SLACK_CREATE_TEAM_POPUP = UserValueName("show_slack_create_team_popup")
  val HIDE_EXTENSION_UPSELL = UserValueName("hide_extension_upsell")
  val TWITTER_SYNC_PROMO = UserValueName("twitter_sync_promo") // show_sync, in_progress

  val LAST_SMS_SENT = UserValueName("last_sms_sent")

  val UPDATED_LIBRARIES_LAST_SEEN = UserValueName("updated_libraries_last_seen")
  val COMPANY_NAME = UserValueName("company_name")
  val HIDE_COMPANY_NAME = UserValueName("hide_company_name")

  val IGNORE_FOR_POTENTIAL_ORGANIZATIONS = UserValueName("ignore_for_potential_organizations")
  val HIDE_EMAIL_DOMAIN_ORGANIZATIONS = UserValueName("hide_email_domain_organizations")
  val PENDING_ORG_DOMAIN_OWNERSHIP_BY_EMAIL = UserValueName("pending_org_domain_ownership_by_email")

  val DEFAULT_LIBRARY_ARRANGEMENT = UserValueName("default_library_arrangement")
  val DEFAULT_KEEP_ARRANGEMENT = UserValueName("default_keep_arrangement")
  val HORRIFYING_LOGGING_METHOD = UserValueName("horrifying_logging_method")
  val LAST_RECORDED_LOCATION = UserValueName("last_recorded_location")

  val FULL_EXPORT_LOCATION = UserValueName("full_export_location")
  val FULL_EXPORT_DOWNLOAD_COUNT = UserValueName("full_export_download_count")

  val LAST_SEEN_ANNOUNCEMENT = UserValueName("last_seen_announcement")
  val SHOW_ANNOUNCEMENT = UserValueName("show_announcement")
  val SEEN_ANNOUNCEMENT_NOTIF = UserValueName("seen_announcement_notif")

  // Please use lower_underscore_case for new value names (and not lowerCamelCase)

  def bookmarkImportContextName(newImportId: String) = UserValueName(s"bookmark_import_${newImportId}_context")
  def importInProgress(networkType: String) = UserValueName(s"import_in_progress_$networkType")

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[UserValueName] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, UserValueName]] = {
      stringBinder.bind(key, params) map {
        case Right(address) => Right(UserValueName(address))
        case Left(string) => Left(s"Unable to bind a valid UserValueName to string $string")
      }
    }
    override def unbind(key: String, userValueName: UserValueName): String = {
      stringBinder.unbind(key, userValueName.name)
    }
  }
}

trait Gender

object Gender {
  case object Male extends Gender
  case object Female extends Gender
  val key = UserValueName.GENDER
  def apply(gender: String): Gender = gender.toLowerCase match {
    case "male" => Male
    case "female" => Female
  }
}

object UserValues {

  trait UserValueHandler[T] {
    def name: UserValueName
    def parse(valOpt: Option[String]): T
    def parseFromMap(valMap: Map[UserValueName, Option[String]]): T = parse(valMap(name))
  }

  case class UserValueBooleanHandler(override val name: UserValueName, default: Boolean) extends UserValueHandler[Boolean] {
    def parse(valOpt: Option[String]): Boolean = valOpt.map(_.toBoolean).getOrElse(default)
  }

  case class UserValueIntHandler(override val name: UserValueName, default: Int) extends UserValueHandler[Int] {
    def parse(valOpt: Option[String]): Int = valOpt.map(_.toInt).getOrElse(default)
  }

  case class UserValueStringHandler(override val name: UserValueName, default: String) extends UserValueHandler[String] {
    def parse(valOpt: Option[String]): String = valOpt.getOrElse(default)
  }

  case class UserValueDateTimeHandler(override val name: UserValueName, default: DateTime) extends UserValueHandler[DateTime] {
    def parse(valOpt: Option[String]): DateTime = valOpt.map(parseStandardTime(_)).getOrElse(default)
  }

  case class UserValueJsValueHandler(override val name: UserValueName, default: JsValue) extends UserValueHandler[JsValue] {
    def parse(valOpt: Option[String]): JsValue = valOpt.map(Json.parse).getOrElse(default)
  }

  val lookHereMode = UserValueBooleanHandler(UserValueName.EXT_LOOK_HERE_MODE, false)
  val enterToSend = UserValueBooleanHandler(UserValueName.ENTER_TO_SEND, true)
  val maxResults = UserValueIntHandler(UserValueName.EXT_MAX_RESULTS, 1)
  val showExtMsgIntro = UserValueBooleanHandler(UserValueName.EXT_SHOW_EXT_MSG_INTRO, true)
  val showExtMoveIntro = UserValueBooleanHandler(UserValueName.EXT_SHOW_MOVE_INTRO, true)
  val quoteAnywhereFtue = UserValueBooleanHandler(UserValueName.QUOTE_ANYWHERE_FTUE, true)
  val hideSocialTooltip = UserValueBooleanHandler(UserValueName.HIDE_SOCIAL_TOOLTIP, false)

  val ExtUserInitPrefs: Seq[UserValueName] = Seq(lookHereMode, enterToSend, maxResults, showExtMsgIntro, showExtMoveIntro, quoteAnywhereFtue, hideSocialTooltip).map(_.name)

  val hasSeenInstall = UserValueBooleanHandler(UserValueName.HAS_SEEN_INSTALL, false)
  val welcomeEmailSent = UserValueBooleanHandler(UserValueName.WELCOME_EMAIL_SENT, false)

  val showDelightedQuestion = UserValueBooleanHandler(UserValueName.SHOW_DELIGHTED_QUESTION, false)
  val lastActive = UserValueDateTimeHandler(UserValueName.LAST_ACTIVE, START_OF_TIME)

  val tagOrdering = UserValueJsValueHandler(UserValueName.USER_COLLECTION_ORDERING, JsArray())
  val recentInteractions = UserValueJsValueHandler(UserValueName.RECENT_INTERACTION, JsArray())

  val userProfileSettings = UserValueJsValueHandler(UserValueName.USER_PROFILE_SETTINGS, Json.obj())

  val hasNoPassword = UserValueBooleanHandler(UserValueName.HAS_NO_PASSWORD, false)

  val lastSmsSent = UserValueDateTimeHandler(UserValueName.LAST_SMS_SENT, START_OF_TIME)

  val twitterSyncAcceptSent = UserValueBooleanHandler(UserValueName.TWITTER_SYNC_ACCEPT_SENT, false)

  val libraryUpdatesLastSeen = UserValueDateTimeHandler(UserValueName.UPDATED_LIBRARIES_LAST_SEEN, START_OF_TIME)

  val ignoreForPotentialOrganizations = UserValueBooleanHandler(UserValueName.IGNORE_FOR_POTENTIAL_ORGANIZATIONS, default = false)

  val hideEmailDomainOrganizations = UserValueJsValueHandler(UserValueName.HIDE_EMAIL_DOMAIN_ORGANIZATIONS, default = JsArray())

  val pendingOrgDomainOwnershipByEmail = UserValueJsValueHandler(UserValueName.PENDING_ORG_DOMAIN_OWNERSHIP_BY_EMAIL, default = Json.obj())

  val lastSeenAnnouncement = UserValueDateTimeHandler(UserValueName.LAST_SEEN_ANNOUNCEMENT, START_OF_TIME)
}
