package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.{ LibraryAccess, Library, User }
import com.keepit.notify.info.{ UsingDbSubset, NotificationInfo, NeedInfo }
import com.keepit.notify.model.{ NonGroupingNotificationKind, NotificationKind, Recipient, NotificationEvent }
import com.keepit.social.BasicUser
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

object LibraryCollabInviteAccepted extends NonGroupingNotificationKind[LibraryCollabInviteAccepted] {

  override val name: String = "library_collab_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryCollabInviteAccepted.apply, unlift(LibraryCollabInviteAccepted.unapply))

  override def info(event: LibraryCollabInviteAccepted): UsingDbSubset[NotificationInfo] = {
    import NeedInfo._
    UsingDbSubset(Seq(
      user(event.accepterId), library(event.libraryId), userImageUrl(event.accepterId), libraryInfo(event.libraryId)
    )) { subset =>
      val accepter = subset.user(event.accepterId)
      val invitedLib = subset.library(event.libraryId)
      val accepterImage = subset.userImageUrl(event.accepterId)
      val invitedLibInfo = subset.libraryInfo(event.libraryId)
      NotificationInfo(
        url = Path(accepter.username.value).encode.absolute,
        imageUrl = accepterImage,
        title = s"${accepter.firstName} is now collaborating on ${invitedLib.name}",
        body = s"You invited ${accepter.firstName} to join ${invitedLib.name}",
        linkText = s"See ${accepter.firstName}’s profile",
        extraJson = Some(Json.obj(
          "follower" -> BasicUser.fromUser(accepter),
          "library" -> Json.toJson(invitedLibInfo)
        ))
      )
    }
  }

}

case class LibraryFollowInviteAccepted(
    recipient: Recipient,
    time: DateTime,
    accepterId: Id[User],
    libraryId: Id[Library]) extends LibraryEvent {

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

  override def info(event: LibraryFollowInviteAccepted): UsingDbSubset[NotificationInfo] = {
    import NeedInfo._
    UsingDbSubset(Seq(
      user(event.accepterId), library(event.libraryId), userImageUrl(event.accepterId), libraryInfo(event.libraryId)
    )) { subset =>
      val accepter = subset.user(event.accepterId)
      val acceptedLib = subset.library(event.libraryId)
      val accepterImage = subset.userImageUrl(event.accepterId)
      val acceptedLibInfo = subset.libraryInfo(event.libraryId)
      NotificationInfo(
        url = Path(accepter.username.value).encode.absolute,
        imageUrl = accepterImage,
        title = s"${accepter.firstName} is now following ${acceptedLib.name}",
        body = s"You invited ${accepter.firstName} to join ${acceptedLib.name}",
        linkText = s"See ${accepter.firstName}’s profile",
        extraJson = Some(Json.obj(
          "follower" -> BasicUser.fromUser(accepter),
          "library" -> Json.toJson(acceptedLibInfo)
        ))
      )
    }
  }

}

case class LibraryNewCollabInvite(
    recipient: Recipient,
    time: DateTime,
    inviterId: Id[User],
    libraryId: Id[Library]) extends LibraryEvent {

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

  override def info(event: LibraryNewCollabInvite): UsingDbSubset[NotificationInfo] = {
    import NeedInfo._
    UsingDbSubset(Seq(
      user(event.inviterId), userImageUrl(event.inviterId), library(event.libraryId), libraryInfo(event.libraryId),
      libraryUrl(event.libraryId), libraryOwner(event.libraryId)
    )) { subset =>
      val inviter = subset.user(event.inviterId)
      val inviterImage = subset.userImageUrl(event.inviterId)
      val invitedLib = subset.library(event.libraryId)
      val invitedLibInfo = subset.libraryInfo(event.libraryId)
      val invitedLibUrl = subset.libraryUrl(event.libraryId)
      val invitedLibOwner = subset.libraryOwner(event.libraryId)
      NotificationInfo(
        url = invitedLibUrl,
        imageUrl = inviterImage,
        title = s"${inviter.firstName} ${inviter.lastName} invited you to collaborate on a library!",
        body = s"Help ${invitedLibOwner.firstName} by sharing your knowledge in the library ${invitedLib.name}.", // todo doesn't _really_ make any sense
        linkText = "Visit library",
        extraJson = Some(Json.obj(
          "inviter" -> inviter,
          "library" -> Json.toJson(invitedLibInfo),
          "access" -> LibraryAccess.READ_WRITE
        ))
      )
    }
  }

}

case class LibraryNewFollowInvite(
    recipient: Recipient,
    time: DateTime,
    inviterId: Id[User],
    libraryId: Id[Library]) extends LibraryEvent {

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

  override def info(event: LibraryNewFollowInvite): UsingDbSubset[NotificationInfo] = {
    import NeedInfo._
    UsingDbSubset(Seq(
      user(event.inviterId), userImageUrl(event.inviterId), library(event.libraryId), libraryInfo(event.libraryId),
      libraryUrl(event.libraryId), libraryOwner(event.libraryId)
    )) { subset =>
      val inviter = subset.user(event.inviterId)
      val inviterImage = subset.userImageUrl(event.inviterId)
      val libraryIn = subset.library(event.libraryId)
      val libraryInInfo = subset.libraryInfo(event.libraryId)
      val libraryInUrl = subset.libraryUrl(event.libraryId)
      val libraryInOwner = subset.libraryOwner(event.libraryId)
      NotificationInfo(
        url = libraryInUrl,
        imageUrl = inviterImage,
        title = s"${inviter.firstName} ${inviter.lastName} invited you to follow a library!",
        body = s"Browse keeps in ${libraryIn.name} to find some interesting gems kept by ${libraryIn.name}.", //same
        linkText = "Visit library",
        extraJson = Some(Json.obj(
          "inviter" -> inviter,
          "library" -> Json.toJson(libraryInInfo),
          "access" -> LibraryAccess.READ_ONLY
        ))
      )
    }
  }

}
