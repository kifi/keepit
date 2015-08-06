package com.keepit.notify.model

import com.keepit.common.db.Id
import com.keepit.model.{ Library, User }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

sealed abstract class NotificationEvent(val toUser: Id[User], val time: DateTime, val kind: NKind)

object NotificationEvent {

  implicit val format = new OFormat[NotificationEvent] {

    override def reads(json: JsValue): JsResult[NotificationEvent] = {
      val kind = (json \ "kind").as[NKind]
      JsSuccess(json.as[NotificationEvent](kind.format.asInstanceOf[Reads[NotificationEvent]]))
    }

    override def writes(o: NotificationEvent): JsObject = {
      Json.obj(
        "kind" -> Json.toJson(o.kind)
      ) ++ o.kind.format.asInstanceOf[Writes[NotificationEvent]].writes(o).asInstanceOf[JsObject]
    }

  }

}

case class NewFollower(
  override val toUser: Id[User],
  override val time: DateTime,
  followerId: Id[User],
  library: Library) extends NotificationEvent(toUser, time, NewFollower)

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
  override val toUser: Id[User],
  override val time: DateTime,
  collaboratorId: Id[User],
  library: Library) extends NotificationEvent(toUser, time, NewCollaborator)

object NewCollaborator extends NotificationKind[NewCollaborator] {

  override val name: String = "new_follower"

  override implicit val format = (
    (__ \ "toUser").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "collaboratorId").format[Id[User]] and
    (__ \ "library").format[Library]
  )(NewCollaborator.apply, unlift(NewCollaborator.unapply))

  override def shouldGroupWith(newAction: NewCollaborator, existingActions: Set[NewCollaborator]): Boolean = false

}
