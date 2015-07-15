package com.keepit.model

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics }
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
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
  val DELIGHTED_SURVEY_PERMANENT = UserExperimentType("permanent_delighted_survey")
  val SPECIAL_CURATOR = UserExperimentType("special_curator")
  val LIBRARIES = UserExperimentType("libraries")
  val SEND_DIGEST_EMAIL_ON_REFRESH = UserExperimentType("send_digest_email_on_refresh")
  val GRAPH_BASED_PEOPLE_TO_INVITE = UserExperimentType("graph_based_people_to_invite")
  val CORTEX_NEW_MODEL = UserExperimentType("cortex_new_model")
  val CURATOR_DIVERSE_TOPIC_RECOS = UserExperimentType("curator_diverse_topic_recos")
  val NEW_PUBLIC_FEED = UserExperimentType("new_public_feed")
  val PLAIN_EMAIL = UserExperimentType("plain_email")
  val GRATIFICATION_EMAIL = UserExperimentType("gratification_email")

  val PROFILES_BETA = UserExperimentType("profiles_beta")
  val TWITTER_BETA = UserExperimentType("twitter_beta")
  val ACTIVITY_EMAIL = UserExperimentType("activity_email")
  val ALL_KEEPS_VIEW = UserExperimentType("all_keeps_view")
  val EXPLICIT_SOCIAL_POSTING = UserExperimentType("explicit_social_posting")
  val RELATED_PAGE_INFO = UserExperimentType("related_page_info")
  val NEXT_GEN_RECOS = UserExperimentType("next_gen_recos")
  val RECO_FASTLANE = UserExperimentType("reco_fastlane")
  val RECO_SUBSAMPLE = UserExperimentType("reco_subsample")
  val APPLY_RECO_FEEDBACK = UserExperimentType("apply_reco_feedback")
  val ORGANIZATION = UserExperimentType("organization")
  val SEARCH_LAB = UserExperimentType("search_lab")

  val _ALL = ADMIN :: AUTO_GEN :: FAKE :: BYPASS_ABUSE_CHECKS :: VISITED :: NO_SEARCH_EXPERIMENTS ::
    DEMO :: EXTENSION_LOGGING :: SHOW_HIT_SCORES :: SHOW_DISCUSSIONS ::
    MOBILE_REDIRECT :: DELIGHTED_SURVEY_PERMANENT :: SPECIAL_CURATOR :: LIBRARIES :: SEND_DIGEST_EMAIL_ON_REFRESH ::
    GRAPH_BASED_PEOPLE_TO_INVITE :: CORTEX_NEW_MODEL :: CURATOR_DIVERSE_TOPIC_RECOS ::
    NEW_PUBLIC_FEED :: PROFILES_BETA :: TWITTER_BETA :: ACTIVITY_EMAIL :: ALL_KEEPS_VIEW :: EXPLICIT_SOCIAL_POSTING :: RELATED_PAGE_INFO :: NEXT_GEN_RECOS ::
    RECO_FASTLANE :: RECO_SUBSAMPLE :: APPLY_RECO_FEEDBACK :: PLAIN_EMAIL :: GRATIFICATION_EMAIL :: ORGANIZATION :: SEARCH_LAB :: Nil

  private val _ALL_MAP: Map[String, UserExperimentType] = _ALL.map(e => e.value -> e).toMap

  def get(str: String): UserExperimentType = UserExperimentType(str.toLowerCase.trim)

  def getUserStatus(experiments: Set[UserExperimentType]): String = {
    if (experiments.contains(FAKE)) FAKE.value
    else if (experiments.contains(ADMIN)) ADMIN.value
    else "standard"
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
