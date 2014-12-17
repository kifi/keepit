package com.keepit.model

import com.google.inject.Inject
import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, Key, StringCacheImpl }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json.{ JsArray, JsValue, Json }
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
  override val version = 3
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
  val AVAILABLE_INVITES = UserValueName("availableInvites")
  val HAS_SEEN_INSTALL = UserValueName("has_seen_install")
  val WELCOME_EMAIL_SENT = UserValueName("welcomeEmailSent")
  val SHOW_DELIGHTED_QUESTION = UserValueName("show_delighted_question")
  val LAST_ACTIVE = UserValueName("last_active")
  val GENDER = UserValueName("gender")
  val USER_COLLECTION_ORDERING = UserValueName("user_collection_ordering")
  val BOOKMARK_IMPORT_LAST_START = UserValueName("bookmark_import_last_start")
  val BOOKMARK_IMPORT_DONE = UserValueName("bookmark_import_done")
  val BOOKMARK_IMPORT_TOTAL = UserValueName("bookmark_import_total")
  val USER_DESCRIPTION = UserValueName("user_description")
  val PENDING_PRIMARY_EMAIL = UserValueName("pending_primary_email")
  val FRIENDS_NOTIFIED_ABOUT_JOINING = UserValueName("friendsNotifiedAboutJoining") // no longer in use
  val CONTACTS_NOTIFIED_ABOUT_JOINING = UserValueName("contactsNotifiedAboutJoining")
  val UPDATED_USER_CONNECTIONS = UserValueName("updated_user_connections")
  val SITE_SHOW_LIBRARY_INTRO = UserValueName("site_show_library_intro")
  val RECENT_INTERACTION = UserValueName("recent_interaction")
  val KIFI_CAMPAIGN_ID = UserValueName("kifi_campaign_id")
  val LAST_DIGEST_EMAIL_SCHEDULED_AT = UserValueName("last_digest_email_scheduled_at")
  val SENT_EMAIL_CONFIRMATION = UserValueName("sent_email_confirmation")
  val LATEST_EMAIL_TIPS_SENT = UserValueName("latest_email_tips")
  val LIBRARY_SORTING_PREF = UserValueName("library_sorting_pref")

  // User Profile Settings
  val USER_PROFILE_SETTINGS = UserValueName("user_profile_settings")
  val SHOW_FOLLOWED_LIBRARIES = UserValueName("show_followed_libraries") // show libraries I follow

  // temp for library callouts for existing user. remove after users know about libraries (Oct 26 2014)
  // library_callout_shown tag_callout_shown guide_callout_shown
  val LIBRARY_CALLOUT_SHOWN = UserValueName("library_callout_shown")
  val TAG_CALLOUT_SHOWN = UserValueName("tag_callout_shown")
  val GUIDE_CALLOUT_SHOWN = UserValueName("guide_callout_shown")

  val AUTO_SHOW_GUIDE = UserValueName("auto_show_guide")

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

  val lookHereMode = UserValueBooleanHandler(UserValueName.EXT_LOOK_HERE_MODE, true)
  val enterToSend = UserValueBooleanHandler(UserValueName.ENTER_TO_SEND, true)
  val maxResults = UserValueIntHandler(UserValueName.EXT_MAX_RESULTS, 1)
  val showExtMsgIntro = UserValueBooleanHandler(UserValueName.EXT_SHOW_EXT_MSG_INTRO, true)
  val showLibraryIntro = UserValueBooleanHandler(UserValueName.EXT_SHOW_LIBRARY_INTRO, false)

  val ExtUserInitPrefs: Seq[UserValueName] = Seq(lookHereMode, enterToSend, maxResults, showExtMsgIntro, showLibraryIntro).map(_.name)

  val availableInvites = UserValueIntHandler(UserValueName.AVAILABLE_INVITES, 1000)
  val hasSeenInstall = UserValueBooleanHandler(UserValueName.HAS_SEEN_INSTALL, false)
  val welcomeEmailSent = UserValueBooleanHandler(UserValueName.WELCOME_EMAIL_SENT, false)

  val showDelightedQuestion = UserValueBooleanHandler(UserValueName.SHOW_DELIGHTED_QUESTION, false)
  val lastActive = UserValueDateTimeHandler(UserValueName.LAST_ACTIVE, START_OF_TIME)

  val tagOrdering = UserValueJsValueHandler(UserValueName.USER_COLLECTION_ORDERING, JsArray())
  val recentInteractions = UserValueJsValueHandler(UserValueName.RECENT_INTERACTION, JsArray())

  val userProfileSettings = UserValueJsValueHandler(UserValueName.USER_PROFILE_SETTINGS, Json.obj())
}

object UserValueSettings {
  val defaultSettings: Map[UserValueName, Boolean] = Map(
    UserValueName.SHOW_FOLLOWED_LIBRARIES -> true
  )

  def retrieveSetting(targetVal: UserValueName, userVals: JsValue): Boolean = {
    retrieveSettings(Set(targetVal), userVals)(targetVal)
  }

  def retrieveSettings(targetVals: Set[UserValueName], userVals: JsValue): Map[UserValueName, Boolean] = {
    targetVals.map { userVal =>
      userVal -> (userVals \ userVal.name).asOpt[Boolean].getOrElse(defaultSettings(userVal))
    }.toMap
  }
}
