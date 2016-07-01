package com.keepit.model

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics }
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.mvc.QueryStringBindable
import scala.concurrent.duration._
import com.keepit.common.json.TraversableFormat
import play.api.libs.json._

case class UserExperiment(
    id: Option[Id[UserExperiment]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    experimentType: UserExperimentType,
    state: State[UserExperiment] = UserExperimentStates.ACTIVE) extends ModelWithState[UserExperiment] {
  def withId(id: Id[UserExperiment]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserExperiment]) = this.copy(state = state)
  def isActive: Boolean = this.state == UserExperimentStates.ACTIVE
}

final case class UserExperimentType(value: String) {
  override def toString = value
}

object UserExperimentType {

  implicit val format: Format[UserExperimentType] = Format(
    __.read[String].map(UserExperimentType(_)),
    new Writes[UserExperimentType] { def writes(o: UserExperimentType) = JsString(o.value) }
  )

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[UserExperimentType] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, UserExperimentType]] = {
      stringBinder.bind(key, params) map {
        case Right(value) => Right(UserExperimentType(value))
        case _ => Left("Unable to bind a ExperimentType")
      }
    }
    override def unbind(key: String, experimentType: UserExperimentType): String = {
      stringBinder.unbind(key, experimentType.value)
    }
  }

  val ADMIN = UserExperimentType("admin")
  val AUTO_GEN = UserExperimentType("autogen")
  val FAKE = UserExperimentType("fake")
  val BYPASS_ABUSE_CHECKS = UserExperimentType("bypass_abuse_checks")
  val VISITED = UserExperimentType("visited")
  val NO_SEARCH_EXPERIMENTS = UserExperimentType("no search experiments")
  val DEMO = UserExperimentType("demo")
  val EXTENSION_LOGGING = UserExperimentType("extension_logging")
  val SHOW_HIT_SCORES = UserExperimentType("show_hit_scores")
  val SHOW_DISCUSSIONS = UserExperimentType("show_discussions")
  val MOBILE_REDIRECT = UserExperimentType("mobile_redirect")
  val SPECIAL_CURATOR = UserExperimentType("special_curator")
  val GRAPH_BASED_PEOPLE_TO_INVITE = UserExperimentType("graph_based_people_to_invite")
  val CORTEX_NEW_MODEL = UserExperimentType("cortex_new_model")
  val CURATOR_DIVERSE_TOPIC_RECOS = UserExperimentType("curator_diverse_topic_recos")
  val PLAIN_EMAIL = UserExperimentType("plain_email")

  val ACTIVITY_EMAIL = UserExperimentType("activity_email")
  val EXPLICIT_SOCIAL_POSTING = UserExperimentType("explicit_social_posting")
  val ANNOUNCE_NEW_TWITTER_LIBRARY = UserExperimentType("announce_new_twitter_lib")
  val RELATED_PAGE_INFO = UserExperimentType("related_page_info")
  val NEXT_GEN_RECOS = UserExperimentType("next_gen_recos")
  val RECO_FASTLANE = UserExperimentType("reco_fastlane")
  val RECO_SUBSAMPLE = UserExperimentType("reco_subsample")
  val APPLY_RECO_FEEDBACK = UserExperimentType("apply_reco_feedback")
  val SEARCH_LAB = UserExperimentType("search_lab")
  val READ_IT_LATER = UserExperimentType("read_id_later")
  val NEW_NOTIFS_SYSTEM = UserExperimentType("new_notifs_system")
  val CREATE_TEAM = UserExperimentType("create_team")
  val SLACK = UserExperimentType("slack")
  val CUSTOM_LIBRARY_ORDERING = UserExperimentType("custom_library_ordering")
  val DISCUSSION_FEED_FILTERS = UserExperimentType("discussion_feed_filters")
  val KEEP_PAGE_RHR = UserExperimentType("keep_page_rhr")
  val RHR_ALPHA_SORTING = UserExperimentType("rhr_alpha_sorting")
  val ADD_KEEP_RECIPIENTS = UserExperimentType("add_keep_recipients")

  val ANNOUNCED_WIND_DOWN = UserExperimentType("announced_wind_down")
  val SYSTEM_READ_ONLY = UserExperimentType("system_read_only")
  val SYSTEM_EXPORT_ONLY = UserExperimentType("system_export_only")

  val _ALL = ADMIN :: AUTO_GEN :: FAKE :: BYPASS_ABUSE_CHECKS :: VISITED :: NO_SEARCH_EXPERIMENTS ::
    DEMO :: EXTENSION_LOGGING :: SHOW_HIT_SCORES :: SHOW_DISCUSSIONS ::
    MOBILE_REDIRECT :: SPECIAL_CURATOR ::
    GRAPH_BASED_PEOPLE_TO_INVITE :: CORTEX_NEW_MODEL :: CURATOR_DIVERSE_TOPIC_RECOS ::
    ACTIVITY_EMAIL :: EXPLICIT_SOCIAL_POSTING :: RELATED_PAGE_INFO :: NEXT_GEN_RECOS ::
    RECO_FASTLANE :: RECO_SUBSAMPLE :: APPLY_RECO_FEEDBACK :: PLAIN_EMAIL :: SEARCH_LAB ::
    NEW_NOTIFS_SYSTEM :: CREATE_TEAM :: SLACK :: CUSTOM_LIBRARY_ORDERING ::
    DISCUSSION_FEED_FILTERS :: KEEP_PAGE_RHR :: RHR_ALPHA_SORTING :: ANNOUNCE_NEW_TWITTER_LIBRARY :: ADD_KEEP_RECIPIENTS ::
    ANNOUNCED_WIND_DOWN :: SYSTEM_READ_ONLY :: SYSTEM_EXPORT_ONLY :: Nil

  // only the ExperimentTypes in this list will be tracked as user properties in analytics
  val _TRACK_FOR_ANALYTICS = Set(EXPLICIT_SOCIAL_POSTING, RELATED_PAGE_INFO, ACTIVITY_EMAIL)

  private val _ALL_MAP: Map[String, UserExperimentType] = _ALL.map(e => e.value -> e).toMap

  def get(str: String): UserExperimentType = UserExperimentType(str.toLowerCase.trim)

  def getUserStatus(experiments: Set[UserExperimentType]): String = {
    if (experiments.contains(FAKE)) FAKE.value
    else if (experiments.contains(ADMIN)) ADMIN.value
    else "standard"
  }

  def getBuzzState(experiments: Set[UserExperimentType]): String = {
    val DEFAULT_BUZZ_STATE = ""
    val highestPriorityExperiment = {
      if (experiments.contains(SYSTEM_EXPORT_ONLY)) Some(SYSTEM_EXPORT_ONLY)
      else if (experiments.contains(SYSTEM_READ_ONLY)) Some(SYSTEM_READ_ONLY)
      else if (experiments.contains(ANNOUNCED_WIND_DOWN)) Some(ANNOUNCED_WIND_DOWN)
      else None
    }
    highestPriorityExperiment.map(_.value).getOrElse(DEFAULT_BUZZ_STATE)
  }

  private val kifiDomains = Set("kifi.com", "42go.com")
  private val testDomains = Set("tfbnw.net", "mailinator.com", "kyfy.com") // tfbnw.net is for fake facebook accounts
  private val tagRe = """(?<=\+)[^@+]*(?=(?:\+|$))""".r

  def getExperimentForEmail(email: EmailAddress): Set[UserExperimentType] = {
    val Array(local, host) = email.address.split('@')
    val tags = tagRe.findAllIn(local).toSet
    if (kifiDomains.contains(host) && tags.exists(_.startsWith("autogen"))) {
      Set(FAKE, AUTO_GEN)
    } else if (kifiDomains.contains(host) && tags.exists { t => t.startsWith("test") || t.startsWith("utest") }) {
      Set(FAKE)
    } else if (testDomains.contains(host)) {
      Set(FAKE)
    } else {
      Set.empty
    }
  }
}

object UserExperimentStates extends States[UserExperiment] {
  implicit val formatter = State.format[UserExperimentType]
}

case class UserExperimentUserIdKey(userId: Id[User]) extends Key[Seq[UserExperimentType]] {
  override val version = 3
  val namespace = "user_experiment_user_id"
  def toKey(): String = userId.id.toString
}

class UserExperimentCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserExperimentUserIdKey, Seq[UserExperimentType]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(TraversableFormat.seq[UserExperimentType])

case object AllFakeUsersKey extends Key[Set[Id[User]]] {
  override val version = 2
  val namespace = "fake_users"
  def toKey(): String = "all"
}

class AllFakeUsersCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[AllFakeUsersKey.type, Set[Id[User]]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(TraversableFormat.set(Id.format[User])) {
}
