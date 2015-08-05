package com.keepit.notify.model

import com.keepit.common.db.Id
import com.keepit.model.{Library, User}
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

class NotificationAction(toUser: Id[User], time: DateTime) {

  val kind = implicitly[NotificationKind[this.type]]

}

case class NewFollower(
  toUser: Id[User],
  time: DateTime,
  followerId: Id[User],
  library: Library) extends NotificationAction(
    toUser,
    time)

object NewFollower extends NotificationKind[NewFollower] {

  override val name: String = "new_follower"

  override implicit val format = (
    (__ \ "toUser").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "followerId").format[Id[User]] and
    (__ \ "library").format[Library]
  )(NewFollower.apply, unlift(NewFollower.unapply))

  override def shouldGroupWith(newAction: NewFollower, existingActions: Set[NewFollower]): Boolean = false

}

case class NewCollaborator(
  toUser: Id[User],
  time: DateTime,
  collaboratorId: Id[User],
  library: Library) extends NotificationAction(
    toUser,
    time)
