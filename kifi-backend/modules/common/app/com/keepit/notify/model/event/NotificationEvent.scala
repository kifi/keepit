package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.notify.model._
import com.keepit.social.SocialNetworkType
import org.joda.time.{Seconds, Duration, DateTime}
import com.keepit.common.core.traversableOnceExtensionOps
import play.api.data.validation.ValidationError
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
  inviteeId: Id[User],
  inviterId: Id[User]) extends NotificationEvent {

  type N = NewConnectionInvite
  val kind = NewConnectionInvite

}

object NewConnectionInvite extends GroupingNotificationKind[NewConnectionInvite, (Id[User], Id[User])] {

  override val name: String = "new_connection_invite"

  // For compatibility with previous connection invites without invitee
  val inviteeFormat: OFormat[Id[User]] = OFormat(
    js =>
      (js \ "inviteeId").validate[Id[User]]
        .orElse((js \ "recipient").validate[Recipient].collect(ValidationError("expected recipient to be a user")) {
          case UserRecipient(id) => id
        }),
    id => Json.obj("inviteeId" -> id)
  )

  override implicit val format: Format[NewConnectionInvite] = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    inviteeFormat and
    (__ \ "inviterId").format[Id[User]]
  )(NewConnectionInvite.apply, unlift(NewConnectionInvite.unapply))

  override def getIdentifier(that: NewConnectionInvite): (Id[User], Id[User]) = that.inviterId -> that.inviteeId

  override def shouldGroupWith(newEvent: NewConnectionInvite, existingEvents: Set[NewConnectionInvite]): Boolean =
    Set(newEvent.inviterId -> newEvent.inviteeId) == existingEvents.map(evt => evt.inviterId -> evt.inviteeId)

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
  keptAt: DateTime,
  keeperId: Option[Id[User]],
  keepId: Id[Keep],
  libraryId: Id[Library]) extends NotificationEvent {

  type N = LibraryNewKeep
  val kind = LibraryNewKeep

}


object LibraryNewKeep extends GroupingNotificationKind[LibraryNewKeep, (Recipient, Id[Library])] {
  override val name: String = "library_new_keep"

  def fromOldData(r: Recipient, t: DateTime, kt: Option[DateTime], uId: Option[Id[User]], kId: Id[Keep], lId: Id[Library]) =
      LibraryNewKeep(r, t, kt getOrElse t, uId, kId, lId)
  def toOldData(lnk: LibraryNewKeep) =
    (lnk.recipient, lnk.time, Some(lnk.keptAt), lnk.keeperId, lnk.keepId, lnk.libraryId)

  override implicit val format: Format[LibraryNewKeep] = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "keptAt").formatNullable[DateTime] and
    (__ \ "keeperId").formatNullable[Id[User]] and
    (__ \ "keepId").format[Id[Keep]] and
    (__ \ "libraryId").format[Id[Library]]
  )(fromOldData, toOldData)

  override def getIdentifier(that: LibraryNewKeep): (Recipient, Id[Library]) = (that.recipient, that.libraryId)

  private val maxAddedAtDifference = Duration.standardSeconds(30)
  private val recentThreshold = Duration.standardDays(1)
  private val minKeptAtDifference = Duration.standardMinutes(5)
  override def shouldGroupWith(newEvent: LibraryNewKeep, existingEvents: Set[LibraryNewKeep]): Boolean = {
    def keepWasAddedShortlyAfterExistingKeeps = existingEvents.map(_.time).exists { otherAddedAt =>
      Seconds.secondsBetween(otherAddedAt, newEvent.time).toStandardDuration isShorterThan maxAddedAtDifference
    }
    def keepIsVeryOld = Seconds.secondsBetween(newEvent.keptAt, newEvent.time).toStandardDuration isLongerThan recentThreshold
    def keepsWereOriginallyFarApart = existingEvents.map(_.keptAt).forall { otherKeptAt =>
      Seconds.secondsBetween(otherKeptAt, newEvent.keptAt).toStandardDuration isLongerThan minKeptAtDifference
    }
    keepWasAddedShortlyAfterExistingKeeps && (keepIsVeryOld || keepsWereOriginallyFarApart)
  }
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

case class OrgMemberJoined(
  recipient: Recipient,
  time: DateTime,
  memberId: Id[User],
  orgId: Id[Organization]) extends NotificationEvent {

  type N = OrgMemberJoined
  val kind = OrgMemberJoined
}

object OrgMemberJoined extends NonGroupingNotificationKind[OrgMemberJoined] {

  override val name: String = "org_member_joined"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "memberId").format[Id[User]] and
      (__ \ "orgId").format[Id[Organization]]
    )(OrgMemberJoined.apply, unlift(OrgMemberJoined.unapply))
}

case class RewardCreditApplied(
  recipient: Recipient,
  time: DateTime,
  description: String,
  orgId: Id[Organization]) extends NotificationEvent {

  type N = RewardCreditApplied
  val kind = RewardCreditApplied
}

object RewardCreditApplied extends NonGroupingNotificationKind[RewardCreditApplied] {

  override val name: String = "reward_credit_applied"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
      (__ \ "time").format[DateTime] and
      (__ \ "description").format[String] and
      (__ \ "orgId").format[Id[Organization]]
    )(RewardCreditApplied.apply, unlift(RewardCreditApplied.unapply))
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
