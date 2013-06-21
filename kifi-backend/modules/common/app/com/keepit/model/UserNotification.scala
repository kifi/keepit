package com.keepit.model

import org.joda.time.DateTime
import com.keepit.common.db._
import com.keepit.common.time._

import play.api.libs.json.JsValue

case class UserNotificationDetails(payload: JsValue) extends AnyVal

case class UserNotification(
  id: Option[Id[UserNotification]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  externalId: ExternalId[UserNotification] = ExternalId[UserNotification](),
  category: UserNotificationCategory,
  details: UserNotificationDetails,
  commentId: Option[Id[Comment]],
  subsumedId: Option[Id[UserNotification]],
  state: State[UserNotification] = UserNotificationStates.UNDELIVERED) extends ModelWithExternalId[UserNotification] {
  def withId(id: Id[UserNotification]): UserNotification = copy(id = Some(id))
  def withUpdateTime(now: DateTime): UserNotification = this.copy(updatedAt = now)
  def isActive: Boolean = state != UserNotificationStates.INACTIVE
  def withState(state: State[UserNotification]): UserNotification = copy(state = state)
}

object UserNotificationStates {
  val INACTIVE = State[UserNotification]("inactive")
  val UNDELIVERED = State[UserNotification]("undelivered")
  val DELIVERED = State[UserNotification]("delivered")
  val VISITED = State[UserNotification]("visited")
  val SUBSUMED = State[UserNotification]("subsumed")
}

case class UserNotificationCategory(name: String) extends AnyVal {
  override def toString = name
}

object UserNotificationCategories {
  val COMMENT = UserNotificationCategory("comment")
  val MESSAGE = UserNotificationCategory("message")
  val GLOBAL = UserNotificationCategory("global")
}


