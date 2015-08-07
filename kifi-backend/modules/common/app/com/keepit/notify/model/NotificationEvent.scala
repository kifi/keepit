package com.keepit.notify.model

import com.keepit.common.db.Id
import com.keepit.model.{ Organization, Keep, Library, User }
import com.keepit.common.time._
import com.keepit.social.SocialNetworkType
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

sealed abstract class NotificationEvent(val userId: Id[User], val time: DateTime, val kind: NKind)

object NotificationEvent {

  implicit val format = new OFormat[NotificationEvent] {

    override def reads(json: JsValue): JsResult[NotificationEvent] = {
      val kind = (json \ "kind").as[NKind]
      JsSuccess(json.as[NotificationEvent](kind.format.asInstanceOf[Reads[NotificationEvent]]))
    }

    override def writes(o: NotificationEvent): JsObject = {
      Json.obj(
        "kind" -> Json.toJson(o.kind)
      ) ++ o.kind.format.asInstanceOf[Writes[NotificationEvent]].writes(o).as[JsObject]
    }

  }

}

case class NewKeepActivity(
  override val userId: Id[User],
  override val time: DateTime,
  keeperId: Id[User],
  keepId: Id[Keep],
  libraryId: Id[Library]) extends NotificationEvent(userId, time, NewKeepActivity)

object NewKeepActivity extends NotificationKind[NewKeepActivity] {

  override val name: String = "new_keep_activity"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "keeperId").format[Id[User]] and
    (__ \ "keepId").format[Id[Keep]] and
    (__ \ "libraryId").format[Id[Library]]
  )(NewKeepActivity.apply, unlift(NewKeepActivity.unapply))

  override def shouldGroupWith(newEvent: NewKeepActivity, existingEvents: Set[NewKeepActivity]): Boolean = false

}

// todo missing, system-global notification
// todo missing, system notification

case class NewSocialConnection(
  override val userId: Id[User],
  override val time: DateTime,
  friendId: Id[User],
  networkType: Option[SocialNetworkType]) extends NotificationEvent(userId, time, NewSocialConnection)

object NewSocialConnection extends NotificationKind[NewSocialConnection] {

  override val name: String = "new_social_connection"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "friendId").format[Id[User]] and
    (__ \ "networkType").formatNullable[SocialNetworkType]
  )(NewSocialConnection.apply, unlift(NewSocialConnection.unapply))

  override def shouldGroupWith(newEvent: NewSocialConnection, existingEvents: Set[NewSocialConnection]): Boolean = false

}

case class OwnedLibraryNewFollower(
  override val userId: Id[User],
  override val time: DateTime,
  followerId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent(userId, time, OwnedLibraryNewFollower)

object OwnedLibraryNewFollower extends NotificationKind[OwnedLibraryNewFollower] {

  override val name: String = "owned_library_new_follower"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "followerId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(OwnedLibraryNewFollower.apply, unlift(OwnedLibraryNewFollower.unapply))

  override def shouldGroupWith(newEvent: OwnedLibraryNewFollower, existingEvents: Set[OwnedLibraryNewFollower]): Boolean = false

}

case class OwnedLibraryNewCollaborator(
  override val userId: Id[User],
  override val time: DateTime,
  collaboratorId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent(userId, time, OwnedLibraryNewCollaborator)

object OwnedLibraryNewCollaborator extends NotificationKind[OwnedLibraryNewCollaborator] {

  override val name: String = "owned_library_new_collaborator"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "collaboratorId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(OwnedLibraryNewCollaborator.apply, unlift(OwnedLibraryNewCollaborator.unapply))

  override def shouldGroupWith(newEvent: OwnedLibraryNewCollaborator, existingEvents: Set[OwnedLibraryNewCollaborator]): Boolean = false

}

case class LibraryNewKeep(
  override val userId: Id[User],
  override val time: DateTime,
  keeperId: Id[User],
  keepId: Id[Keep],
  libraryId: Id[Library]) extends NotificationEvent(userId, time, LibraryNewKeep)

object LibraryNewKeep extends NotificationKind[LibraryNewKeep] {

  override val name: String = "library_new_keep"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "keeperId").format[Id[User]] and
    (__ \ "keepId").format[Id[Keep]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryNewKeep.apply, unlift(LibraryNewKeep.unapply))

  override def shouldGroupWith(newEvent: LibraryNewKeep, existingEvents: Set[LibraryNewKeep]): Boolean = false

}

case class NewConnection(
  override val userId: Id[User],
  override val time: DateTime,
  connectionId: Id[User]) extends NotificationEvent(userId, time, NewConnection)

object NewConnection extends NotificationKind[NewConnection] {

  override val name: String = "new_connection"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "connectionId").format[Id[User]]
  )(NewConnection.apply, unlift(NewConnection.unapply))

  override def shouldGroupWith(newEvent: NewConnection, existingEvents: Set[NewConnection]): Boolean = false

}

case class LibraryCollabInviteAccepted(
  override val userId: Id[User],
  override val time: DateTime,
  accepterId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent(userId, time, LibraryCollabInviteAccepted)

object LibraryCollabInviteAccepted extends NotificationKind[LibraryCollabInviteAccepted] {

  override val name: String = "library_collab_invite_accepted"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryCollabInviteAccepted.apply, unlift(LibraryCollabInviteAccepted.unapply))

  override def shouldGroupWith(newEvent: LibraryCollabInviteAccepted, existingEvents: Set[LibraryCollabInviteAccepted]): Boolean = false

}

case class LibraryFollowInviteAccepted(
  override val userId: Id[User],
  override val time: DateTime,
  accepterId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent(userId, time, LibraryFollowInviteAccepted)

object LibraryFollowInviteAccepted extends NotificationKind[LibraryFollowInviteAccepted] {

  override val name: String = "library_follow_invite_accepted"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryFollowInviteAccepted.apply, unlift(LibraryFollowInviteAccepted.unapply))

  override def shouldGroupWith(newEvent: LibraryFollowInviteAccepted, existingEvents: Set[LibraryFollowInviteAccepted]): Boolean = false

}

case class LibraryNewCollabInvite(
  override val userId: Id[User],
  override val time: DateTime,
  inviterId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent(userId, time, LibraryNewCollabInvite)

object LibraryNewCollabInvite extends NotificationKind[LibraryNewCollabInvite] {

  override val name: String = "library_new_collab_invite"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryNewCollabInvite.apply, unlift(LibraryNewCollabInvite.unapply))

  override def shouldGroupWith(newEvent: LibraryNewCollabInvite, existingEvents: Set[LibraryNewCollabInvite]): Boolean = false

}

case class LibraryNewFollowInvite(
  override val userId: Id[User],
  override val time: DateTime,
  inviterId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent(userId, time, LibraryNewFollowInvite)

object LibraryNewFollowInvite extends NotificationKind[LibraryNewFollowInvite] {

  override val name: String = "library_new_follow_invite"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryNewFollowInvite.apply, unlift(LibraryNewFollowInvite.unapply))

  override def shouldGroupWith(newEvent: LibraryNewFollowInvite, existingEvents: Set[LibraryNewFollowInvite]): Boolean = false

}

case class OwnedLibraryNewCollabInvite(
  override val userId: Id[User],
  override val time: DateTime,
  inviterId: Id[User],
  inviteeIds: Set[Id[User]],
  libraryId: Id[Library]) extends NotificationEvent(userId, time, OwnedLibraryNewCollabInvite)

object OwnedLibraryNewCollabInvite extends NotificationKind[OwnedLibraryNewCollabInvite] {

  override val name: String = "owned_library_new_collab_invite"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "inviteeIds").format[Set[Id[User]]] and
    (__ \ "libraryId").format[Id[Library]]
  )(OwnedLibraryNewCollabInvite.apply, unlift(OwnedLibraryNewCollabInvite.unapply))

  override def shouldGroupWith(newEvent: OwnedLibraryNewCollabInvite, existingEvents: Set[OwnedLibraryNewCollabInvite]): Boolean = false

}

case class OwnedLibraryNewFollowInvite(
  override val userId: Id[User],
  override val time: DateTime,
  inviterId: Id[User],
  inviteeIds: Set[Id[User]],
  libraryId: Id[Library]) extends NotificationEvent(userId, time, OwnedLibraryNewFollowInvite)

object OwnedLibraryNewFollowInvite extends NotificationKind[OwnedLibraryNewFollowInvite] {

  override val name: String = "owned_library_new_follow_invite"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "inviteeIds").format[Set[Id[User]]] and
    (__ \ "libraryId").format[Id[Library]]
  )(OwnedLibraryNewFollowInvite.apply, unlift(OwnedLibraryNewFollowInvite.unapply))

  override def shouldGroupWith(newEvent: OwnedLibraryNewFollowInvite, existingEvents: Set[OwnedLibraryNewFollowInvite]): Boolean = false

}

case class OrgNewInvite(
  override val userId: Id[User],
  override val time: DateTime,
  inviterId: Id[User],
  inviteeIds: Set[Id[User]],
  orgId: Id[Organization]) extends NotificationEvent(userId, time, OrgNewInvite)

object OrgNewInvite extends NotificationKind[OrgNewInvite] {

  override val name: String = "org_new_invite"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "inviteeIds").format[Set[Id[User]]] and
    (__ \ "orgId").format[Id[Organization]]
  )(OrgNewInvite.apply, unlift(OrgNewInvite.unapply))

  override def shouldGroupWith(newEvent: OrgNewInvite, existingEvents: Set[OrgNewInvite]): Boolean = false

}

case class OrgInviteAccepted(
  override val userId: Id[User],
  override val time: DateTime,
  inviterId: Id[User],
  inviteeIds: Set[Id[User]],
  orgId: Id[Organization]) extends NotificationEvent(userId, time, OrgInviteAccepted)

object OrgInviteAccepted extends NotificationKind[OrgInviteAccepted] {

  override val name: String = "org_invite_accepted"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "inviteeIds").format[Set[Id[User]]] and
    (__ \ "orgId").format[Id[Organization]]
  )(OrgInviteAccepted.apply, unlift(OrgInviteAccepted.unapply))

  override def shouldGroupWith(newEvent: OrgInviteAccepted, existingEvents: Set[OrgInviteAccepted]): Boolean = false

}

// todo missing, social new library through twitter (unused?)
// todo missing, social new follower through twitter (unused?)

case class SocialContactJoined(
  override val userId: Id[User],
  override val time: DateTime,
  joinerId: Id[User]) extends NotificationEvent(userId, time, SocialContactJoined)

object SocialContactJoined extends NotificationKind[SocialContactJoined] {

  override val name: String = "org_new_invite"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "joinerId").format[Id[User]]
  )(SocialContactJoined.apply, unlift(SocialContactJoined.unapply))

  override def shouldGroupWith(newEvent: SocialContactJoined, existingEvents: Set[SocialContactJoined]): Boolean = false

}

case class NewConnectionInvite(
  override val userId: Id[User],
  override val time: DateTime,
  inviterId: Id[User]) extends NotificationEvent(userId, time, NewConnectionInvite)

object NewConnectionInvite extends NotificationKind[NewConnectionInvite] {

  override val name: String = "new_connection_invite"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]]
  )(NewConnectionInvite.apply, unlift(NewConnectionInvite.unapply))

  override def shouldGroupWith(newEvent: NewConnectionInvite, existingEvents: Set[NewConnectionInvite]): Boolean = false

}

case class ConnectionInviteAccepted(
  override val userId: Id[User],
  override val time: DateTime,
  accepterId: Id[User]) extends NotificationEvent(userId, time, ConnectionInviteAccepted)

object ConnectionInviteAccepted extends NotificationKind[ConnectionInviteAccepted] {

  override val name: String = "connection_invite_accepted"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]]
  )(ConnectionInviteAccepted.apply, unlift(ConnectionInviteAccepted.unapply))

  override def shouldGroupWith(newEvent: ConnectionInviteAccepted, existingEvents: Set[ConnectionInviteAccepted]): Boolean = false

}

case class DepressedRobotGrumble(
  override val userId: Id[User],
  override val time: DateTime,
  robotName: String,
  grumblingAbout: String) extends NotificationEvent(userId, time, DepressedRobotGrumble)

object DepressedRobotGrumble extends NotificationKind[DepressedRobotGrumble] {

  override val name: String = "depressed_robot_grumble"

  override implicit val format = (
    (__ \ "userId").format[Id[User]] and
    (__ \ "time").format[DateTime] and
    (__ \ "robotName").format[String] and
    (__ \ "grumblingAbout").format[String]
  )(DepressedRobotGrumble.apply, unlift(DepressedRobotGrumble.unapply))

  override def shouldGroupWith(newEvent: DepressedRobotGrumble, existingEvents: Set[DepressedRobotGrumble]): Boolean = false

}
