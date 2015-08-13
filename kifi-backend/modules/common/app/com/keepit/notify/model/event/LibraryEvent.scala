package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.model.{Library, User}
import com.keepit.notify.model.{NotificationKind, Recipient, NotificationEvent}
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

trait LibraryEvent extends NotificationEvent

case class LibraryCollabInviteAccepted(
  recipient: Recipient,
  time: DateTime,
  accepterId: Id[User],
  libraryId: Id[Library]) extends LibraryEvent {

  val kind = LibraryCollabInviteAccepted

}

object LibraryCollabInviteAccepted extends NotificationKind[LibraryCollabInviteAccepted] {

  override val name: String = "library_collab_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "accepterId").format[Id[User]] and
      (__ \ "libraryId").format[Id[Library]]
    )(LibraryCollabInviteAccepted.apply, unlift(LibraryCollabInviteAccepted.unapply))

  override def shouldGroupWith(newEvent: LibraryCollabInviteAccepted, existingEvents: Set[LibraryCollabInviteAccepted]): Boolean = false

}

case class LibraryFollowInviteAccepted(
  recipient: Recipient,
  time: DateTime,
  accepterId: Id[User],
  libraryId: Id[Library]) extends LibraryEvent {

  val kind = LibraryFollowInviteAccepted

}

object LibraryFollowInviteAccepted extends NotificationKind[LibraryFollowInviteAccepted] {

  override val name: String = "library_follow_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "accepterId").format[Id[User]] and
      (__ \ "libraryId").format[Id[Library]]
    )(LibraryFollowInviteAccepted.apply, unlift(LibraryFollowInviteAccepted.unapply))

  override def shouldGroupWith(newEvent: LibraryFollowInviteAccepted, existingEvents: Set[LibraryFollowInviteAccepted]): Boolean = false

}

case class LibraryNewCollabInvite(
  recipient: Recipient,
  time: DateTime,
  inviterId: Id[User],
  libraryId: Id[Library]) extends LibraryEvent {

  val kind = LibraryNewCollabInvite

}

object LibraryNewCollabInvite extends NotificationKind[LibraryNewCollabInvite] {

  override val name: String = "library_new_collab_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "inviterId").format[Id[User]] and
      (__ \ "libraryId").format[Id[Library]]
    )(LibraryNewCollabInvite.apply, unlift(LibraryNewCollabInvite.unapply))

  override def shouldGroupWith(newEvent: LibraryNewCollabInvite, existingEvents: Set[LibraryNewCollabInvite]): Boolean = false

}

case class LibraryNewFollowInvite(
  recipient: Recipient,
  time: DateTime,
  inviterId: Id[User],
  libraryId: Id[Library]) extends LibraryEvent {

  val kind = LibraryNewFollowInvite

}

object LibraryNewFollowInvite extends NotificationKind[LibraryNewFollowInvite] {

  override val name: String = "library_new_follow_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "inviterId").format[Id[User]] and
      (__ \ "libraryId").format[Id[Library]]
    )(LibraryNewFollowInvite.apply, unlift(LibraryNewFollowInvite.unapply))

  override def shouldGroupWith(newEvent: LibraryNewFollowInvite, existingEvents: Set[LibraryNewFollowInvite]): Boolean = false

}
