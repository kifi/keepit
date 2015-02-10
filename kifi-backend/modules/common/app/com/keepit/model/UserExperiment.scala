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
    experimentType: ExperimentType,
    state: State[UserExperiment] = UserExperimentStates.ACTIVE) extends ModelWithState[UserExperiment] {
  def withId(id: Id[UserExperiment]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserExperiment]) = this.copy(state = state)
  def isActive: Boolean = this.state == UserExperimentStates.ACTIVE
}

final case class ExperimentType(value: String) {
  override def toString = value
}

object ExperimentType {

  implicit val format: Format[ExperimentType] = Format(
    __.read[String].map(ExperimentType(_)),
    new Writes[ExperimentType] { def writes(o: ExperimentType) = JsString(o.value) }
  )

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[ExperimentType] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, ExperimentType]] = {
      stringBinder.bind(key, params) map {
        case Right(value) => Right(ExperimentType(value))
        case _ => Left("Unable to bind a ExperimentType")
      }
    }
    override def unbind(key: String, experimentType: ExperimentType): String = {
      stringBinder.unbind(key, experimentType.value)
    }
  }

  val ADMIN = ExperimentType("admin")
  val AUTO_GEN = ExperimentType("autogen")
  val FAKE = ExperimentType("fake")
  val VISITED = ExperimentType("visited")
  val NO_SEARCH_EXPERIMENTS = ExperimentType("no search experiments")
  val CAN_MESSAGE_ALL_USERS = ExperimentType("can message all users")
  val DEMO = ExperimentType("demo")
  val EXTENSION_LOGGING = ExperimentType("extension_logging")
  val SHOW_HIT_SCORES = ExperimentType("show_hit_scores")
  val SHOW_DISCUSSIONS = ExperimentType("show_discussions")
  val MOBILE_REDIRECT = ExperimentType("mobile_redirect")
  val DELIGHTED_SURVEY_PERMANENT = ExperimentType("permanent_delighted_survey")
  val SPECIAL_CURATOR = ExperimentType("special_curator")
  val LIBRARIES = ExperimentType("libraries")
  val SEND_DIGEST_EMAIL_ON_REFRESH = ExperimentType("send_digest_email_on_refresh")
  val GRAPH_BASED_PEOPLE_TO_INVITE = ExperimentType("graph_based_people_to_invite")
  val NEW_KEEP_NOTIFICATIONS = ExperimentType("new_keep_notifications")
  val CORTEX_NEW_MODEL = ExperimentType("cortex_new_model")
  val CURATOR_DIVERSE_TOPIC_RECOS = ExperimentType("curator_diverse_topic_recos")
  val NEW_PUBLIC_FEED = ExperimentType("new_public_feed")
  val PROFILES_BETA = ExperimentType("profiles_beta")
  val TWITTER_BETA = ExperimentType("twitter_beta")
  val ACTIVITY_EMAIL = ExperimentType("activity_email")
  val FACEBOOK_POST = ExperimentType("facebook_post")
  val TWEET_ALL = ExperimentType("tweet_all")
  val ALL_KEEPS_VIEW = ExperimentType("all_keeps_view")
  val USER_SEARCH_ENGINE = ExperimentType("user_search_engine")

  val _ALL = ADMIN :: AUTO_GEN :: FAKE :: VISITED :: NO_SEARCH_EXPERIMENTS ::
    CAN_MESSAGE_ALL_USERS :: DEMO :: EXTENSION_LOGGING :: SHOW_HIT_SCORES :: SHOW_DISCUSSIONS ::
    MOBILE_REDIRECT :: DELIGHTED_SURVEY_PERMANENT :: SPECIAL_CURATOR :: LIBRARIES :: SEND_DIGEST_EMAIL_ON_REFRESH ::
    GRAPH_BASED_PEOPLE_TO_INVITE :: NEW_KEEP_NOTIFICATIONS :: CORTEX_NEW_MODEL :: CURATOR_DIVERSE_TOPIC_RECOS ::
    NEW_PUBLIC_FEED :: PROFILES_BETA :: TWITTER_BETA :: ACTIVITY_EMAIL :: FACEBOOK_POST :: ALL_KEEPS_VIEW :: TWEET_ALL :: USER_SEARCH_ENGINE :: Nil

  private val _ALL_MAP: Map[String, ExperimentType] = _ALL.map(e => e.value -> e).toMap

  def get(str: String): ExperimentType = _ALL_MAP(str.toLowerCase.trim)

  def getUserStatus(experiments: Set[ExperimentType]): String = {
    if (experiments.contains(FAKE)) FAKE.value
    else if (experiments.contains(ADMIN)) ADMIN.value
    else "standard"
  }
}

object UserExperimentStates extends States[UserExperiment] {
  implicit val formatter = State.format[ExperimentType]
}

case class UserExperimentUserIdKey(userId: Id[User]) extends Key[Seq[ExperimentType]] {
  override val version = 3
  val namespace = "user_experiment_user_id"
  def toKey(): String = userId.id.toString
}

class UserExperimentCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserExperimentUserIdKey, Seq[ExperimentType]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(TraversableFormat.seq[ExperimentType])

case object AllFakeUsersKey extends Key[Set[Id[User]]] {
  override val version = 2
  val namespace = "fake_users"
  def toKey(): String = "all"
}

class AllFakeUsersCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[AllFakeUsersKey.type, Set[Id[User]]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(TraversableFormat.set(Id.format[User])) {
}
