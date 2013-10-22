package com.keepit.model

import com.keepit.common.db.{States, Model, State, Id}
import org.joda.time.DateTime
import com.keepit.common.time._

case class UserPictureSource(name: String)
object UserPictureSources {
  val FACEBOOK = UserPictureSource("facebook")
  val LINKEDIN = UserPictureSource("linkedin")
  val USER_UPLOAD = UserPictureSource("user_upload")
}

case class UserPicture(
  id: Option[Id[UserPicture]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  name: String,
  origin: UserPictureSource,
  state: State[UserPicture] = UserPictureStates.ACTIVE
  ) extends Model[UserPicture] {
  def withId(id: Id[UserPicture]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserPicture]) = copy(state = state)
}

object UserPictureStates extends States[UserPicture]
