package com.keepit.curator.model

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.keepit.curator.feedback.ByteArrayCounter
import com.keepit.model.User
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.concurrent.duration.Duration

case class UserRecoFeedbackCounter(
    id: Option[Id[UserRecoFeedbackCounter]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    voteups: ByteArrayCounter,
    voteDowns: ByteArrayCounter,
    state: State[UserRecoFeedbackCounter] = UserRecoFeedbackCounterStates.ACTIVE) extends ModelWithState[UserRecoFeedbackCounter] {

  def withId(id: Id[UserRecoFeedbackCounter]): UserRecoFeedbackCounter = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): UserRecoFeedbackCounter = this.copy(updatedAt = updateTime)
}

object UserRecoFeedbackCounterStates extends States[UserRecoFeedbackCounter]

object UserRecoFeedbackCounter {
  private implicit val idFormat = Id.format[UserRecoFeedbackCounter]
  private implicit val uidFormat = Id.format[User]
  private implicit val stateFormat = State.format[UserRecoFeedbackCounter]
  implicit val format = (
    (__ \ 'id).format[Option[Id[UserRecoFeedbackCounter]]] and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'voteUps).format[ByteArrayCounter] and
    (__ \ 'voteDowns).format[ByteArrayCounter] and
    (__ \ 'state).format[State[UserRecoFeedbackCounter]]
  )(UserRecoFeedbackCounter.apply, unlift(UserRecoFeedbackCounter.unapply))
}

case class UserRecoFeedbackCounterUserKey(userId: Id[User]) extends Key[UserRecoFeedbackCounter] {
  override val version = 1
  val namespace = "user_reco_feedback_counter_by_user"
  def toKey(): String = userId.id.toString
}

class UserRecoFeedbackCounterUserCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserRecoFeedbackCounterUserKey, UserRecoFeedbackCounter](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
