package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.User
import com.keepit.notify.info.ReturnsInfo.{ GetUserImage, PickOne, GetUser }
import com.keepit.notify.info.{ NotificationInfo, ReturnsInfoResult }
import com.keepit.notify.model.{ NotificationKind, Recipient, NotificationEvent }
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

trait ConnectionEvent extends NotificationEvent

case class NewConnectionInvite(
    recipient: Recipient,
    time: DateTime,
    inviterId: Id[User]) extends ConnectionEvent {

  val kind = NewConnectionInvite

}

object NewConnectionInvite extends NotificationKind[NewConnectionInvite] {

  override val name: String = "new_connection_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]]
  )(NewConnectionInvite.apply, unlift(NewConnectionInvite.unapply))

  override def shouldGroupWith(newEvent: NewConnectionInvite, existingEvents: Set[NewConnectionInvite]): Boolean = false

  override def info(events: Set[NewConnectionInvite]): ReturnsInfoResult = for {
    event <- PickOne(events)
    user <- GetUser(event.inviterId)
    userImage <- GetUserImage(event.inviterId)
  } yield NotificationInfo(
    path = Path(user.username.value),
    title = s"${user.firstName} ${user.lastName} wants to connect with you on Kifi",
    body = s"Enjoy ${user.firstName}’s keeps in your search results and message ${user.firstName} directly.",
    linkText = s"Respond to ${user.firstName}’s invitation",
    imageUrl = userImage,
    extraJson = Some(Json.obj(
      "friend" -> BasicUser.fromUser(user)
    ))
  )

}

case class ConnectionInviteAccepted(
    recipient: Recipient,
    time: DateTime,
    accepterId: Id[User]) extends ConnectionEvent {

  val kind = ConnectionInviteAccepted

}

object ConnectionInviteAccepted extends NotificationKind[ConnectionInviteAccepted] {

  override val name: String = "connection_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]]
  )(ConnectionInviteAccepted.apply, unlift(ConnectionInviteAccepted.unapply))

  override def shouldGroupWith(newEvent: ConnectionInviteAccepted, existingEvents: Set[ConnectionInviteAccepted]): Boolean = false

  override def info(events: Set[ConnectionInviteAccepted]): ReturnsInfoResult = for {
    event <- PickOne(events)
    user <- GetUser(event.accepterId)
    userImage <- GetUserImage(event.accepterId)
  } yield NotificationInfo(
    path = Path(user.username.value),
    title = s"${user.firstName} ${user.lastName} accepted your invitation to connect!",
    body = s"Now you will enjoy ${user.firstName}’s keeps in your search results and you can message ${user.firstName} directly.",
    linkText = s"Visit ${user.firstName}’s profile",
    imageUrl = userImage,
    extraJson = Some(Json.obj(
      "friend" -> BasicUser.fromUser(user)
    ))
  )

}

