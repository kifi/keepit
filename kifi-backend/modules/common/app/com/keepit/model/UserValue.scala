package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.kifi.macros.json
import org.joda.time.DateTime
import com.keepit.common.cache.{ StringCacheImpl, FortyTwoCachePlugin, Key }
import play.api.libs.json.{ JsArray, Json, JsValue }
import play.api.mvc.QueryStringBindable
import scala.concurrent.duration.Duration
import com.keepit.model.UserValues.UserValueStringHandler

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
  override val version = 2
  val namespace = "uservalue"
  def toKey(): String = userId.id + "_" + key.name
}
class UserValueCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends StringCacheImpl[UserValueKey](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object UserValueStates extends States[UserValue]

@json case class UserValueName(name: String)

object UserValueName {
  val LOOK_HERE_MODE = UserValueName("ext_look_here_mode")
  val ENTER_TO_SEND = UserValueName("enter_to_send")
  val MAX_RESULTS = UserValueName("ext_max_results")
  val SHOW_EXT_MSG_INTRO = UserValueName("ext_show_ext_msg_intro")
  val AVAILABLE_INVITES = UserValueName("availableInvites")
  val HAS_SEEN_INSTALL = UserValueName("has_seen_install")
  val WELCOME_EMAIL_SENT = UserValueName("welcomeEmailSent")
  val SHOW_DELIGHTED_QUESTION = UserValueName("show_delighted_question")
  val LAST_ACTIVE = UserValueName("last_active")
  val GENDER = UserValueName("gender")
  val USER_SEGMENT = UserValueName("userSegment")
  val EXTENSION_VERSION = UserValueName("extensionVersion")
  val USER_COLLECTION_ORDERING = UserValueName("user_collection_ordering")
  val BOOKMARK_IMPORT_LAST_START = UserValueName("bookmark_import_last_start")
  val BOOKMARK_IMPORT_DONE = UserValueName("bookmark_import_done")
  val BOOKMARK_IMPORT_TOTAL = UserValueName("bookmark_import_total")
  val USER_DESCRIPTION = UserValueName("user_description")
  val PENDING_PRIMARY_EMAIL = UserValueName("pending_primary_email")
  val EXT_SHOW_EXT_MSG_INTRO = UserValueName("ext_show_ext_msg_intro")
  val FRIENDS_NOTIFIED_ABOUT_JOINING = UserValueName("friendsNotifiedAboutJoining")
  val UPDATED_USER_CONNECTIONS = UserValueName("updated_user_connections")
  val SITE_LEFT_COL_WIDTH = UserValueName("site_left_col_width")
  val SITE_WELCOMED = UserValueName("site_welcomed")
  val ONBOARDING_SEEN = UserValueName("onboarding_seen")
  val NON_USER_IDENTIFIER = UserValueName("nonUserIdentifier")
  val NON_USER_KIND = UserValueName("nonUserKind")

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

  val lookHereMode = UserValueBooleanHandler(UserValueName.LOOK_HERE_MODE, true)
  val enterToSend = UserValueBooleanHandler(UserValueName.ENTER_TO_SEND, true)
  val maxResults = UserValueIntHandler(UserValueName.MAX_RESULTS, 1)
  val showExtMsgIntro = UserValueBooleanHandler(UserValueName.SHOW_EXT_MSG_INTRO, true)

  val UserInitPrefs: Seq[UserValueName] = Seq(lookHereMode, enterToSend, maxResults, showExtMsgIntro).map(_.name)

  val availableInvites = UserValueIntHandler(UserValueName.AVAILABLE_INVITES, 1000)
  val hasSeenInstall = UserValueBooleanHandler(UserValueName.HAS_SEEN_INSTALL, false)
  val welcomeEmailSent = UserValueBooleanHandler(UserValueName.WELCOME_EMAIL_SENT, false)

  val showDelightedQuestion = UserValueBooleanHandler(UserValueName.SHOW_DELIGHTED_QUESTION, false)
  val lastActive = UserValueDateTimeHandler(UserValueName.LAST_ACTIVE, START_OF_TIME)

  val tagOrdering = UserValueJsValueHandler(UserValueName.USER_COLLECTION_ORDERING, JsArray())
}
