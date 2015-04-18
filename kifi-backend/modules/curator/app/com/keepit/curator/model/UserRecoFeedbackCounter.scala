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
    upVotes: ByteArrayCounter,
    downVotes: ByteArrayCounter,
    posSignals: ByteArrayCounter,
    negSignals: ByteArrayCounter,
    votesRescaleCount: Int,
    signalsRescaleCount: Int,
    state: State[UserRecoFeedbackCounter] = UserRecoFeedbackCounterStates.ACTIVE) extends ModelWithState[UserRecoFeedbackCounter] {

  def withId(id: Id[UserRecoFeedbackCounter]): UserRecoFeedbackCounter = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): UserRecoFeedbackCounter = this.copy(updatedAt = updateTime)

  private def rescaleVotesOnOverflow(idx: Int): UserRecoFeedbackCounter = {
    this.copy(upVotes = upVotes.rescale(idx), downVotes = downVotes.rescale(idx), votesRescaleCount = this.votesRescaleCount + 1)
  }

  private def rescaleSignalsOnOverflow(idx: Int): UserRecoFeedbackCounter = {
    this.copy(posSignals = posSignals.rescale(idx), negSignals = negSignals.rescale(idx), signalsRescaleCount = this.signalsRescaleCount + 1)
  }

  private def handleSingal(idx: Int, fb: UriRecoFeedbackValue): UserRecoFeedbackCounter = {
    FeedbackIncreConfig.getIncreValue(fb) match {
      case Some(PositiveSingalIncre(x)) =>
        val scaled = if (!posSignals.canIncrement(idx, x)) {
          rescaleSignalsOnOverflow(idx)
        } else this
        scaled.copy(posSignals = scaled.posSignals.increment(idx, x))

      case Some(NegativeSignalIncre(x)) =>
        val scaled = if (!negSignals.canIncrement(idx: Int, x)) {
          rescaleSignalsOnOverflow(idx)
        } else this
        scaled.copy(negSignals = scaled.negSignals.increment(idx, x))

      case None => this
    }
  }

  private def handleVote(idx: Int, fb: UriRecoFeedbackValue): UserRecoFeedbackCounter = {
    fb match {
      case UriRecoFeedbackValue.LIKE =>
        val scaled = if (!upVotes.canIncrement(idx)) {
          rescaleVotesOnOverflow(idx)
        } else this
        scaled.copy(upVotes = scaled.upVotes.increment(idx))

      case UriRecoFeedbackValue.DISLIKE =>
        val scaled = if (!downVotes.canIncrement(idx)) {
          rescaleVotesOnOverflow(idx)
        } else this
        scaled.copy(downVotes = scaled.downVotes.increment(idx))

      case _ => this
    }
  }

  def updateWithFeedback(idx: Int, fb: UriRecoFeedbackValue): UserRecoFeedbackCounter = {
    val updated = handleSingal(idx, fb)
    updated.handleVote(idx, fb)
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
    (__ \ 'upVotes).format[ByteArrayCounter] and
    (__ \ 'downVotes).format[ByteArrayCounter] and
    (__ \ 'posSignals).format[ByteArrayCounter] and
    (__ \ 'negSignals).format[ByteArrayCounter] and
    (__ \ 'votesRescaleCount).format[Int] and
    (__ \ 'signalsRescaleCount).format[Int] and
    (__ \ 'state).format[State[UserRecoFeedbackCounter]]
  )(UserRecoFeedbackCounter.apply, unlift(UserRecoFeedbackCounter.unapply))

  def empty(userId: Id[User], bucketSize: Int) = UserRecoFeedbackCounter(
    userId = userId,
    upVotes = ByteArrayCounter.empty(bucketSize),
    downVotes = ByteArrayCounter.empty(bucketSize),
    posSignals = ByteArrayCounter.empty(bucketSize),
    negSignals = ByteArrayCounter.empty(bucketSize),
    votesRescaleCount = 0,
    signalsRescaleCount = 0)

}

case class UserRecoFeedbackCounterUserKey(userId: Id[User]) extends Key[UserRecoFeedbackCounter] {
  override val version = 2
  val namespace = "user_reco_feedback_counter_by_user"
  def toKey(): String = userId.id.toString
}

class UserRecoFeedbackCounterUserCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserRecoFeedbackCounterUserKey, UserRecoFeedbackCounter](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

sealed trait FeedbackIncreValue
case class PositiveSingalIncre(x: Int) extends FeedbackIncreValue
case class NegativeSignalIncre(x: Int) extends FeedbackIncreValue

object FeedbackIncreConfig {
  import UriRecoFeedbackValue._

  // these values need to be less than 255/2, so that rescale strategy works.
  private val clicked = 1
  private val kept = 5
  private val liked = 5
  private val disliked = 5

  def getIncreValue(fb: UriRecoFeedbackValue): Option[FeedbackIncreValue] = {
    fb match {
      case CLICKED => Some(PositiveSingalIncre(clicked))
      case KEPT => Some(PositiveSingalIncre(kept))
      case LIKE => Some(PositiveSingalIncre(liked))
      case DISLIKE => Some(NegativeSignalIncre(disliked))
      case _ => None
    }
  }
}
