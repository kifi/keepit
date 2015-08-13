package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.notify.model.{NotificationKind, Recipient, NotificationEvent}
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

}

