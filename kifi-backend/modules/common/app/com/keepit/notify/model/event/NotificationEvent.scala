package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.model.{Organization, Library, Keep, User}
import com.keepit.notify.model._
import com.keepit.social.SocialNetworkType
import org.joda.time.DateTime
import play.api.libs.json._
import com.keepit.common.time._
import play.api.libs.functional.syntax._

sealed trait NotificationEvent { self =>

  type N >: self.type <: NotificationEvent

  val recipient: Recipient
  val time: DateTime
  val kind: NotificationKind[N]

}

object NotificationEvent {

  implicit val format = new OFormat[NotificationEvent] {

    override def reads(json: JsValue): JsResult[NotificationEvent] = {
      val kind = (json \ "kind").as[NKind]
      JsSuccess(json.as[NotificationEvent](Reads(kind.format.reads)))
    }

    override def writes(o: NotificationEvent): JsObject = {
      Json.obj(
        "kind" -> Json.toJson(o.kind)
      ) ++ o.kind.format.writes(o).as[JsObject]
    }

  }

}

// todo missing, system-global notification
// todo missing, system notification

case class NewConnectionInvite(
  recipient: Recipient,
  time: DateTime,
  inviterId: Id[User]) extends NotificationEvent {

  type N = NewConnectionInvite
  val kind = NewConnectionInvite

}

object NewConnectionInvite extends NonGroupingNotificationKind[NewConnectionInvite] {

  override val name: String = "new_connection_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]]
  )(NewConnectionInvite.apply, unlift(NewConnectionInvite.unapply))

}

case class ConnectionInviteAccepted(
  recipient: Recipient,
  time: DateTime,
  accepterId: Id[User]) extends NotificationEvent {

  type N = ConnectionInviteAccepted
  val kind = ConnectionInviteAccepted

}

object ConnectionInviteAccepted extends NonGroupingNotificationKind[ConnectionInviteAccepted] {

  override val name: String = "connection_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "accepterId").format[Id[User]]
    )(ConnectionInviteAccepted.apply, unlift(ConnectionInviteAccepted.unapply))

}


case class LibraryNewKeep(
  recipient: Recipient,
  time: DateTime,
  keeperId: Id[User],
  keepId: Id[Keep],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = LibraryNewKeep
  val kind = LibraryNewKeep

}

object LibraryNewKeep extends NonGroupingNotificationKind[LibraryNewKeep] {

  override val name: String = "library_new_keep"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "keeperId").format[Id[User]] and
      (__ \ "keepId").format[Id[Keep]] and
      (__ \ "libraryId").format[Id[Library]]
    )(LibraryNewKeep.apply, unlift(LibraryNewKeep.unapply))

}

// todo is this ever really used/called?
case class NewKeepActivity(
  recipient: Recipient,
  time: DateTime,
  keeperId: Id[User],
  keepId: Id[Keep],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = NewKeepActivity
  val kind = NewKeepActivity

}

object NewKeepActivity extends NonGroupingNotificationKind[NewKeepActivity] {

  override val name: String = "new_keep_activity"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "keeperId").format[Id[User]] and
      (__ \ "keepId").format[Id[Keep]] and
      (__ \ "libraryId").format[Id[Library]]
    )(NewKeepActivity.apply, unlift(NewKeepActivity.unapply))

}


case class LibraryCollabInviteAccepted(
  recipient: Recipient,
  time: DateTime,
  accepterId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = LibraryCollabInviteAccepted
  val kind = LibraryCollabInviteAccepted

}

object LibraryCollabInviteAccepted extends NonGroupingNotificationKind[LibraryCollabInviteAccepted] {

  override val name: String = "library_collab_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "accepterId").format[Id[User]] and
      (__ \ "libraryId").format[Id[Library]]
    )(LibraryCollabInviteAccepted.apply, unlift(LibraryCollabInviteAccepted.unapply))

}

case class LibraryFollowInviteAccepted(
  recipient: Recipient,
  time: DateTime,
  accepterId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = LibraryFollowInviteAccepted
  val kind = LibraryFollowInviteAccepted

}

object LibraryFollowInviteAccepted extends NonGroupingNotificationKind[LibraryFollowInviteAccepted] {

  override val name: String = "library_follow_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "accepterId").format[Id[User]] and
      (__ \ "libraryId").format[Id[Library]]
    )(LibraryFollowInviteAccepted.apply, unlift(LibraryFollowInviteAccepted.unapply))

}

case class LibraryNewCollabInvite(
  recipient: Recipient,
  time: DateTime,
  inviterId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = LibraryNewCollabInvite
  val kind = LibraryNewCollabInvite

}

object LibraryNewCollabInvite extends NonGroupingNotificationKind[LibraryNewCollabInvite] {

  override val name: String = "library_new_collab_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "inviterId").format[Id[User]] and
      (__ \ "libraryId").format[Id[Library]]
    )(LibraryNewCollabInvite.apply, unlift(LibraryNewCollabInvite.unapply))

}

case class LibraryNewFollowInvite(
  recipient: Recipient,
  time: DateTime,
  inviterId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = LibraryNewFollowInvite
  val kind = LibraryNewFollowInvite

}

object LibraryNewFollowInvite extends NonGroupingNotificationKind[LibraryNewFollowInvite] {

  override val name: String = "library_new_follow_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "inviterId").format[Id[User]] and
      (__ \ "libraryId").format[Id[Library]]
    )(LibraryNewFollowInvite.apply, unlift(LibraryNewFollowInvite.unapply))

}


case class NewMessage(
  recipient: Recipient,
  time: DateTime,
  messageThreadId: Long, // need to use long here because MessageThread is only defined in Eliza
  messageId: Long // same here
) extends NotificationEvent {

  type N = NewMessage
  val kind = NewMessage

}

object NewMessage extends NotificationKind[NewMessage] {

  override val name: String = "new_message"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "messageThreadId").format[Long] and
      (__ \ "messageId").format[Long]
    )(NewMessage.apply, unlift(NewMessage.unapply))

  override def groupIdentifier(event: NewMessage): Option[String] = Some(event.messageThreadId.toString)

  override def shouldGroupWith(newEvent: NewMessage, existingEvents: Set[NewMessage]): Boolean = {
    val existing = existingEvents.head
    existing.messageThreadId == newEvent.messageThreadId
  }

}

case class OrgNewInvite(
  recipient: Recipient,
  time: DateTime,
  inviterId: Id[User],
  orgId: Id[Organization]) extends NotificationEvent {

  type N = OrgNewInvite
  val kind = OrgNewInvite

}

object OrgNewInvite extends NonGroupingNotificationKind[OrgNewInvite] {

  override val name: String = "org_new_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "inviterId").format[Id[User]] and
      (__ \ "orgId").format[Id[Organization]]
    )(OrgNewInvite.apply, unlift(OrgNewInvite.unapply))

}

case class OrgInviteAccepted(
  recipient: Recipient,
  time: DateTime,
  accepterId: Id[User],
  orgId: Id[Organization]) extends NotificationEvent {

  type N = OrgInviteAccepted
  val kind = OrgInviteAccepted

}

object OrgInviteAccepted extends NonGroupingNotificationKind[OrgInviteAccepted] {

  override val name: String = "org_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "accepterId").format[Id[User]] and
      (__ \ "orgId").format[Id[Organization]]
    )(OrgInviteAccepted.apply, unlift(OrgInviteAccepted.unapply))

}


case class OwnedLibraryNewCollabInvite(
  recipient: Recipient,
  time: DateTime,
  inviterId: Id[User],
  inviteeId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = OwnedLibraryNewCollabInvite
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

  type N = OwnedLibraryNewFollowInvite
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

  type N = OwnedLibraryNewFollower
  val kind = OwnedLibraryNewFollower

}

object OwnedLibraryNewFollower extends NonGroupingNotificationKind[OwnedLibraryNewFollower] {

  override val name: String = "owned_library_new_follower"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "followerId").format[Id[User]] and
      (__ \ "libraryId").format[Id[Library]]
    )(OwnedLibraryNewFollower.apply, unlift(OwnedLibraryNewFollower.unapply))

}

case class OwnedLibraryNewCollaborator(
  recipient: Recipient,
  time: DateTime,
  collaboratorId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = OwnedLibraryNewCollaborator
  val kind = OwnedLibraryNewCollaborator

}

object OwnedLibraryNewCollaborator extends NonGroupingNotificationKind[OwnedLibraryNewCollaborator] {

  override val name: String = "owned_library_new_collaborator"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "collaboratorId").format[Id[User]] and
      (__ \ "libraryId").format[Id[Library]]
    )(OwnedLibraryNewCollaborator.apply, unlift(OwnedLibraryNewCollaborator.unapply))

}

case class NewSocialConnection(
  recipient: Recipient,
  time: DateTime,
  friendId: Id[User],
  networkType: Option[SocialNetworkType]) extends NotificationEvent {

  type N = NewSocialConnection
  val kind = NewSocialConnection

}

object NewSocialConnection extends NonGroupingNotificationKind[NewSocialConnection] {

  override val name: String = "new_social_connection"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "friendId").format[Id[User]] and
      (__ \ "networkType").formatNullable[SocialNetworkType]
    )(NewSocialConnection.apply, unlift(NewSocialConnection.unapply))

}

// todo missing, social new library through twitter (unused?)
// todo missing, social new follower through twitter (unused?)

case class SocialContactJoined(
  recipient: Recipient,
  time: DateTime,
  joinerId: Id[User]) extends NotificationEvent {

  type N = SocialContactJoined
  val kind = SocialContactJoined

}

object SocialContactJoined extends NonGroupingNotificationKind[SocialContactJoined] {

  override val name: String = "social_contact_joined"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "joinerId").format[Id[User]]
    )(SocialContactJoined.apply, unlift(SocialContactJoined.unapply))

}


case class DepressedRobotGrumble(
    recipient: Recipient,
    time: DateTime,
    robotName: String,
    grumblingAbout: String,
    shouldGroup: Option[Boolean] = None) extends NotificationEvent {

  type N = DepressedRobotGrumble
  val kind = DepressedRobotGrumble

}

object DepressedRobotGrumble extends NotificationKind[DepressedRobotGrumble] {

  override val name: String = "depressed_robot_grumble"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "robotName").format[String] and
      (__ \ "grumblingAbout").format[String] and
      (__ \ "shouldGroup").formatNullable[Boolean]
    )(DepressedRobotGrumble.apply, unlift(DepressedRobotGrumble.unapply))

  override def shouldGroupWith(newEvent: DepressedRobotGrumble, existingEvents: Set[DepressedRobotGrumble]): Boolean = newEvent.shouldGroup.getOrElse(false)

}

