package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.model.{ Library, User }
import com.keepit.notify.model.{ NotificationKind, Recipient, NotificationEvent }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

trait OwnedLibraryEvent extends NotificationEvent

case class OwnedLibraryNewCollabInvite(
    recipient: Recipient,
    time: DateTime,
    inviterId: Id[User],
    inviteeId: Id[User],
    libraryId: Id[Library]) extends NotificationEvent {

  val kind = OwnedLibraryNewCollabInvite

}

object OwnedLibraryNewCollabInvite extends NotificationKind[OwnedLibraryNewCollabInvite] {

  override val name: String = "owned_library_new_collab_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "inviteeId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(OwnedLibraryNewCollabInvite.apply, unlift(OwnedLibraryNewCollabInvite.unapply))

  override def shouldGroupWith(newEvent: OwnedLibraryNewCollabInvite, existingEvents: Set[OwnedLibraryNewCollabInvite]): Boolean = {
    // only check a random event, they should all have the same inviter and library
    val existing = existingEvents.head
    existing.inviterId == newEvent.inviterId && existing.libraryId == newEvent.libraryId
  }

}

case class OwnedLibraryNewFollowInvite(
    recipient: Recipient,
    time: DateTime,
    inviterId: Id[User],
    inviteeId: Id[User],
    libraryId: Id[Library]) extends NotificationEvent {

  val kind = OwnedLibraryNewFollowInvite

}

object OwnedLibraryNewFollowInvite extends NotificationKind[OwnedLibraryNewFollowInvite] {

  override val name: String = "owned_library_new_follow_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "inviteeId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(OwnedLibraryNewFollowInvite.apply, unlift(OwnedLibraryNewFollowInvite.unapply))

  override def shouldGroupWith(newEvent: OwnedLibraryNewFollowInvite, existingEvents: Set[OwnedLibraryNewFollowInvite]): Boolean = {
    // only check a random event, they should all have the same inviter and library
    val existing = existingEvents.head
    existing.inviterId == newEvent.inviterId && existing.libraryId == newEvent.libraryId
  }

}

case class OwnedLibraryNewFollower(
    recipient: Recipient,
    time: DateTime,
    followerId: Id[User],
    libraryId: Id[Library]) extends NotificationEvent {

  val kind = OwnedLibraryNewFollower

}

object OwnedLibraryNewFollower extends NotificationKind[OwnedLibraryNewFollower] {

  override val name: String = "owned_library_new_follower"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "followerId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(OwnedLibraryNewFollower.apply, unlift(OwnedLibraryNewFollower.unapply))

  override def shouldGroupWith(newEvent: OwnedLibraryNewFollower, existingEvents: Set[OwnedLibraryNewFollower]): Boolean = false

}

case class OwnedLibraryNewCollaborator(
    recipient: Recipient,
    time: DateTime,
    collaboratorId: Id[User],
    libraryId: Id[Library]) extends NotificationEvent {

  val kind = OwnedLibraryNewCollaborator

}

object OwnedLibraryNewCollaborator extends NotificationKind[OwnedLibraryNewCollaborator] {

  override val name: String = "owned_library_new_collaborator"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "collaboratorId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(OwnedLibraryNewCollaborator.apply, unlift(OwnedLibraryNewCollaborator.unapply))

  override def shouldGroupWith(newEvent: OwnedLibraryNewCollaborator, existingEvents: Set[OwnedLibraryNewCollaborator]): Boolean = false

}
