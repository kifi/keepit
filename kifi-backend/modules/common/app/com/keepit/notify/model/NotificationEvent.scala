package com.keepit.notify.model

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.{ Organization, Keep, Library, User }
import com.keepit.common.time._
import com.keepit.notify.info.ReturnsInfo.{ GetUserImage, GetUser, PickOne }
import com.keepit.notify.info.{ Args, NotificationInfo, ReturnsInfo, ReturnsInfoResult }
import com.keepit.social.{ BasicUser, SocialNetworkType }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

sealed trait NotificationEvent {

  val recipient: Recipient
  val time: DateTime
  val kind: NKind

}

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
    recipient: Recipient,
    time: DateTime,
    keeperId: Id[User],
    keepId: Id[Keep],
    libraryId: Id[Library]) extends NotificationEvent {

  val kind = NewKeepActivity

}

object NewKeepActivity extends NotificationKind[NewKeepActivity] {

  override val name: String = "new_keep_activity"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
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
    recipient: Recipient,
    time: DateTime,
    friendId: Id[User],
    networkType: Option[SocialNetworkType]) extends NotificationEvent {

  val kind = NewSocialConnection

}

object NewSocialConnection extends NotificationKind[NewSocialConnection] {

  override val name: String = "new_social_connection"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "friendId").format[Id[User]] and
    (__ \ "networkType").formatNullable[SocialNetworkType]
  )(NewSocialConnection.apply, unlift(NewSocialConnection.unapply))

  override def shouldGroupWith(newEvent: NewSocialConnection, existingEvents: Set[NewSocialConnection]): Boolean = false

  def build(
    recipient: Recipient,
    time: DateTime,
    friend: User,
    friendImage: String,
    networkType: Option[SocialNetworkType]): EventArgs =
    EventArgs(
      NewSocialConnection(recipient, time, friend.id.get, networkType)
    ).args("friend" -> friend, "friendImage" -> friendImage)

  override def info(events: Set[NewSocialConnection]): ReturnsInfoResult = for {
    event <- PickOne(events)
    friend <- GetUser(event.friendId, "friend")
    image <- GetUserImage(event.friendId, 0, "friendImage")
  } yield NotificationInfo(
    path = Path(friend.username.value),
    imageUrl = image,
    title = s"You’re connected with ${friend.firstName} ${friend.lastName} on Kifi!",
    body = s"Enjoy ${friend.firstName}’s keeps in your search results and message ${friend.firstName} directly.",
    linkText = "Invite more friends to kifi",
    extraJson = Some(Json.obj(
      "friend" -> BasicUser.fromUser(friend)
    ))
  )

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

case class LibraryNewKeep(
    recipient: Recipient,
    time: DateTime,
    keeperId: Id[User],
    keepId: Id[Keep],
    libraryId: Id[Library]) extends NotificationEvent {

  val kind = LibraryNewKeep

}

object LibraryNewKeep extends NotificationKind[LibraryNewKeep] {

  override val name: String = "library_new_keep"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "keeperId").format[Id[User]] and
    (__ \ "keepId").format[Id[Keep]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryNewKeep.apply, unlift(LibraryNewKeep.unapply))

  override def shouldGroupWith(newEvent: LibraryNewKeep, existingEvents: Set[LibraryNewKeep]): Boolean = false

}

case class LibraryCollabInviteAccepted(
    recipient: Recipient,
    time: DateTime,
    accepterId: Id[User],
    libraryId: Id[Library]) extends NotificationEvent {

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
    libraryId: Id[Library]) extends NotificationEvent {

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
    libraryId: Id[Library]) extends NotificationEvent {

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
    libraryId: Id[Library]) extends NotificationEvent {

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

case class OrgNewInvite(
    recipient: Recipient,
    time: DateTime,
    inviterId: Id[User],
    orgId: Id[Organization]) extends NotificationEvent {

  val kind = OrgNewInvite

}

object OrgNewInvite extends NotificationKind[OrgNewInvite] {

  override val name: String = "org_new_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "orgId").format[Id[Organization]]
  )(OrgNewInvite.apply, unlift(OrgNewInvite.unapply))

  override def shouldGroupWith(newEvent: OrgNewInvite, existingEvents: Set[OrgNewInvite]): Boolean = false

}

case class OrgInviteAccepted(
    recipient: Recipient,
    time: DateTime,
    accepterId: Id[User],
    orgId: Id[Organization]) extends NotificationEvent {

  val kind = OrgInviteAccepted

}

object OrgInviteAccepted extends NotificationKind[OrgInviteAccepted] {

  override val name: String = "org_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]] and
    (__ \ "orgId").format[Id[Organization]]
  )(OrgInviteAccepted.apply, unlift(OrgInviteAccepted.unapply))

  override def shouldGroupWith(newEvent: OrgInviteAccepted, existingEvents: Set[OrgInviteAccepted]): Boolean = false

}

// todo missing, social new library through twitter (unused?)
// todo missing, social new follower through twitter (unused?)

case class SocialContactJoined(
    recipient: Recipient,
    time: DateTime,
    joinerId: Id[User]) extends NotificationEvent {

  val kind = SocialContactJoined

}

object SocialContactJoined extends NotificationKind[SocialContactJoined] {

  override val name: String = "social_contact_joined"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "joinerId").format[Id[User]]
  )(SocialContactJoined.apply, unlift(SocialContactJoined.unapply))

  override def shouldGroupWith(newEvent: SocialContactJoined, existingEvents: Set[SocialContactJoined]): Boolean = false

}

case class NewConnectionInvite(
    recipient: Recipient,
    time: DateTime,
    inviterId: Id[User]) extends NotificationEvent {

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
    accepterId: Id[User]) extends NotificationEvent {

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

case class DepressedRobotGrumble(
    recipient: Recipient,
    time: DateTime,
    robotName: String,
    grumblingAbout: String,
    shouldGroup: Option[Boolean] = None) extends NotificationEvent {

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

case class NewMessage(
    recipient: Recipient,
    time: DateTime,
    messageThreadId: Long, // need to use long here because MessageThread is only defined in Eliza
    messageId: Long // same here
    ) extends NotificationEvent {

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
