package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.model.{Organization, Library, Keep, User}
import com.keepit.notify.model._
import com.keepit.social.SocialNetworkType
import org.joda.time.DateTime
import play.api.libs.json._
import com.keepit.common.time._

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

object NewConnectionInvite extends NewConnectionInviteImpl

case class ConnectionInviteAccepted(
  recipient: Recipient,
  time: DateTime,
  accepterId: Id[User]) extends NotificationEvent {

  type N = ConnectionInviteAccepted
  val kind = ConnectionInviteAccepted

}

object ConnectionInviteAccepted extends ConnectionInviteAcceptedImpl

case class LibraryNewKeep(
  recipient: Recipient,
  time: DateTime,
  keeperId: Id[User],
  keepId: Id[Keep],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = LibraryNewKeep
  val kind = LibraryNewKeep

}

object LibraryNewKeep extends LibraryNewKeepImpl

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

object NewKeepActivity extends NewKeepActivityImpl

case class LibraryCollabInviteAccepted(
  recipient: Recipient,
  time: DateTime,
  accepterId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = LibraryCollabInviteAccepted
  val kind = LibraryCollabInviteAccepted

}

object LibraryCollabInviteAccepted extends LibraryCollabInviteAcceptedImpl

case class LibraryFollowInviteAccepted(
  recipient: Recipient,
  time: DateTime,
  accepterId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = LibraryFollowInviteAccepted
  val kind = LibraryFollowInviteAccepted

}

object LibraryFollowInviteAccepted extends LibraryFollowInviteAcceptedImpl

case class LibraryNewCollabInvite(
  recipient: Recipient,
  time: DateTime,
  inviterId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = LibraryNewCollabInvite
  val kind = LibraryNewCollabInvite

}

object LibraryNewCollabInvite extends LibraryNewCollabInviteImpl

case class LibraryNewFollowInvite(
  recipient: Recipient,
  time: DateTime,
  inviterId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = LibraryNewFollowInvite
  val kind = LibraryNewFollowInvite

}

object LibraryNewFollowInvite extends LibraryNewFollowInviteImpl

case class NewMessage(
  recipient: Recipient,
  time: DateTime,
  messageThreadId: Long, // need to use long here because MessageThread is only defined in Eliza
  messageId: Long // same here
) extends NotificationEvent {

  type N = NewMessage
  val kind = NewMessage

}

object NewMessage extends NewMessageImpl

case class OrgNewInvite(
  recipient: Recipient,
  time: DateTime,
  inviterId: Id[User],
  orgId: Id[Organization]) extends NotificationEvent {

  type N = OrgNewInvite
  val kind = OrgNewInvite

}

object OrgNewInvite extends OrgNewInviteImpl

case class OrgInviteAccepted(
  recipient: Recipient,
  time: DateTime,
  accepterId: Id[User],
  orgId: Id[Organization]) extends NotificationEvent {

  type N = OrgInviteAccepted
  val kind = OrgInviteAccepted

}

object OrgInviteAccepted extends OrgInviteAcceptedImpl

case class OwnedLibraryNewCollabInvite(
  recipient: Recipient,
  time: DateTime,
  inviterId: Id[User],
  inviteeId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = OwnedLibraryNewCollabInvite
  val kind = OwnedLibraryNewCollabInvite

}

object OwnedLibraryNewCollabInvite extends OwnedLibraryNewCollabInviteImpl

case class OwnedLibraryNewFollowInvite(
  recipient: Recipient,
  time: DateTime,
  inviterId: Id[User],
  inviteeId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = OwnedLibraryNewFollowInvite
  val kind = OwnedLibraryNewFollowInvite

}

object OwnedLibraryNewFollowInvite extends OwnedLibraryNewFollowInviteImpl

case class OwnedLibraryNewFollower(
  recipient: Recipient,
  time: DateTime,
  followerId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = OwnedLibraryNewFollower
  val kind = OwnedLibraryNewFollower

}

object OwnedLibraryNewFollower extends OwnedLibraryNewFollowerImpl

case class OwnedLibraryNewCollaborator(
  recipient: Recipient,
  time: DateTime,
  collaboratorId: Id[User],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = OwnedLibraryNewCollaborator
  val kind = OwnedLibraryNewCollaborator

}

object OwnedLibraryNewCollaborator extends OwnedLibraryNewCollaboratorImpl

case class NewSocialConnection(
  recipient: Recipient,
  time: DateTime,
  friendId: Id[User],
  networkType: Option[SocialNetworkType]) extends NotificationEvent {

  type N = NewSocialConnection
  val kind = NewSocialConnection

}

object NewSocialConnection extends NewSocialConnectionImpl

// todo missing, social new library through twitter (unused?)
// todo missing, social new follower through twitter (unused?)

case class SocialContactJoined(
  recipient: Recipient,
  time: DateTime,
  joinerId: Id[User]) extends NotificationEvent {

  type N = SocialContactJoined
  val kind = SocialContactJoined

}

object SocialContactJoined extends SocialContactJoinedImpl

case class DepressedRobotGrumble(
    recipient: Recipient,
    time: DateTime,
    robotName: String,
    grumblingAbout: String,
    shouldGroup: Option[Boolean] = None) extends NotificationEvent {

  type N = DepressedRobotGrumble
  val kind = DepressedRobotGrumble

}

object DepressedRobotGrumble extends DepressedRobotGrumbleImpl
