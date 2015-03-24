package com.keepit.model

import org.joda.time.{ LocalTime, DateTime }
import com.keepit.common.db._
import com.keepit.common.time._

case class ActivityPushTask(
    id: Option[Id[ActivityPushTask]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    state: State[ActivityPushTask] = ActivityPushTaskStates.ACTIVE,
    lastPush: Option[DateTime] = None,
    lastActiveTime: LocalTime,
    lastActiveDate: DateTime) extends Model[ActivityPushTask] {

  def withId(id: Id[ActivityPushTask]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

  def withLastActivity(when: DateTime): ActivityPushTask = copy(lastActiveDate = when, lastActiveTime = when.toLocalTimeInZone(DEFAULT_DATE_TIME_ZONE))

}

object ActivityPushTaskStates extends States[ActivityPushTask] {
  val NO_DEVICES = State[ActivityPushTask]("no_devices")
}
