package com.keepit.model

import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key}
import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import scala.concurrent.duration._
import com.keepit.serializer.TraversableFormat

case class UserExperiment (
  id: Option[Id[UserExperiment]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  experimentType: State[ExperimentType],
  state: State[UserExperiment] = UserExperimentStates.ACTIVE
) extends Model[UserExperiment] {
  def withId(id: Id[UserExperiment]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserExperiment]) = this.copy(state = state)
  def isActive: Boolean = this.state == UserExperimentStates.ACTIVE
}

final case class ExperimentType(value: String)

object ExperimentTypes {
  val ADMIN = State[ExperimentType]("admin")
  val WEBSITE = State[ExperimentType]("website")
  val WEBSITE_FRIENDS = State[ExperimentType]("website friends")
  val FAKE = State[ExperimentType]("fake")
  val BLOCK = State[ExperimentType]("block")
  val INACTIVE = State[ExperimentType]("inactive")
  val NO_SEARCH_EXPERIMENTS = State[ExperimentType]("no search experiments")
  val CAN_INVITE = State[ExperimentType]("can invite")
  val CAN_MESSAGE_ALL_USERS = State[ExperimentType]("can message all users")

  def apply(str: String): State[ExperimentType] = str.toLowerCase.trim match {
    case ADMIN.value => ADMIN
    case WEBSITE.value => WEBSITE
    case WEBSITE_FRIENDS.value => WEBSITE_FRIENDS
    case FAKE.value => FAKE
    case BLOCK.value => BLOCK
    case INACTIVE.value => INACTIVE
    case NO_SEARCH_EXPERIMENTS.value => NO_SEARCH_EXPERIMENTS
    case CAN_INVITE.value => CAN_INVITE
    case CAN_MESSAGE_ALL_USERS.value => CAN_MESSAGE_ALL_USERS
  }
}

object UserExperimentStates extends States[UserExperiment] {
  implicit val formatter = State.format[ExperimentType]
}

case class UserExperimentUserIdKey(userId: Id[User]) extends Key[Seq[State[ExperimentType]]] {
  override val version = 2
  val namespace = "user_experiment_user_id"
  def toKey(): String = userId.id.toString
}

class UserExperimentCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[UserExperimentUserIdKey, Seq[State[ExperimentType]]](innermostPluginSettings, innerToOuterPluginSettings:_*)(TraversableFormat.seq(State.format[ExperimentType]))
