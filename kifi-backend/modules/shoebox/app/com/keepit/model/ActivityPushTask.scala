package com.keepit.model

import org.joda.time.{ LocalTime, DateTime }
import com.keepit.common.db._
import com.keepit.common.time._

import scala.concurrent.duration.Duration

case class ActivityPushTask(
    id: Option[Id[ActivityPushTask]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    state: State[ActivityPushTask] = ActivityPushTaskStates.ACTIVE,
    lastPush: Option[DateTime] = None,
    lastActiveTime: LocalTime,
    lastActiveDate: DateTime, // when was user active last? open app, keep, user creation, etc
    nextPush: Option[DateTime], // when we should push next?
    backoff: Option[Duration] // how long did we previously wait?
    ) extends Model[ActivityPushTask] {

  def withId(id: Id[ActivityPushTask]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

  def withLastActivity(when: DateTime): ActivityPushTask = copy(lastActiveDate = when, lastActiveTime = when.toLocalTimeInZone(DEFAULT_DATE_TIME_ZONE))

}

object ActivityPushTaskStates extends States[ActivityPushTask] {
  val NO_DEVICES = State[ActivityPushTask]("no_devices")
  val OPTED_OUT = State[ActivityPushTask]("opted_out")
}
