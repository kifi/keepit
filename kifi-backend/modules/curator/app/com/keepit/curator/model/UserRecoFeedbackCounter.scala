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
    voteUps: ByteArrayCounter,
    voteDowns: ByteArrayCounter,
    state: State[UserRecoFeedbackCounter] = UserRecoFeedbackCounterStates.ACTIVE) extends ModelWithState[UserRecoFeedbackCounter] {

  def withId(id: Id[UserRecoFeedbackCounter]): UserRecoFeedbackCounter = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): UserRecoFeedbackCounter = this.copy(updatedAt = updateTime)

  private def rescaleOnOverflow(idx: Int): UserRecoFeedbackCounter = {
    val (ups, downs) = (voteUps.get(idx), voteDowns.get(idx))
    val (newUps, newDowns) = (voteUps.set(idx, ups / 2), voteDowns.set(idx, downs / 2))
    this.copy(voteUps = newUps, voteDowns = newDowns)
  }

  def updateWithFeedback(idx: Int, fb: UriRecoFeedbackValue): UserRecoFeedbackCounter = {
    FeedbackIncreConfig.getIncreValue(fb) match {
      case Some(VoteUpIncre(x)) =>
        val scaled = if (!voteUps.canIncrement(idx, x)) {
          rescaleOnOverflow(idx)
        } else this
        scaled.copy(voteUps = scaled.voteUps.increment(idx, x))

      case Some(VoteDownIncre(x)) =>
        val scaled = if (!voteDowns.canIncrement(idx: Int, x)) {
          rescaleOnOverflow(idx)
        } else this
        scaled.copy(voteDowns = scaled.voteDowns.increment(idx, x))

      case None => this
    }
  }
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

  def empty(userId: Id[User], bucketSize: Int) = UserRecoFeedbackCounter(userId = userId, voteUps = ByteArrayCounter.empty(bucketSize), voteDowns = ByteArrayCounter.empty(bucketSize))

}

case class UserRecoFeedbackCounterUserKey(userId: Id[User]) extends Key[UserRecoFeedbackCounter] {
  override val version = 1
  val namespace = "user_reco_feedback_counter_by_user"
  def toKey(): String = userId.id.toString
}

class UserRecoFeedbackCounterUserCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserRecoFeedbackCounterUserKey, UserRecoFeedbackCounter](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

sealed trait FeedbackIncreValue
case class VoteUpIncre(x: Int) extends FeedbackIncreValue
case class VoteDownIncre(x: Int) extends FeedbackIncreValue

object FeedbackIncreConfig {
  import UriRecoFeedbackValue._

  // these values need to be less than 255/2, so that rescale strategy works.
  private val clicked = 1
  private val kept = 5
  private val liked = 5
  private val disliked = 5

  def getIncreValue(fb: UriRecoFeedbackValue): Option[FeedbackIncreValue] = {
    fb match {
      case CLICKED => Some(VoteUpIncre(clicked))
      case KEPT => Some(VoteUpIncre(kept))
      case LIKE => Some(VoteUpIncre(liked))
      case DISLIKE => Some(VoteDownIncre(disliked))
      case _ => None
    }
  }
}
