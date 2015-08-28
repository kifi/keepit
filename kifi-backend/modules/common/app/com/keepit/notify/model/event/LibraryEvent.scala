package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.{ LibraryAccess, Library, User }
import com.keepit.notify.info.DbViewKey._
import com.keepit.notify.info._
import com.keepit.notify.model.{ NonGroupingNotificationKind, NotificationKind, Recipient }
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

trait LibraryCollabInviteAcceptedImpl extends NonGroupingNotificationKind[LibraryCollabInviteAccepted] {

  override val name: String = "library_collab_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryCollabInviteAccepted.apply, unlift(LibraryCollabInviteAccepted.unapply))

  override def info(event: LibraryCollabInviteAccepted): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(Requests(
      user(event.accepterId), library(event.libraryId)
    )) { subset =>
      val accepterInfo = user(event.accepterId).lookup(subset)
      val accepter = accepterInfo.user
      val invitedLib = library(event.libraryId).lookup(subset)
      NotificationInfo(
        url = accepterInfo.path.encode.absolute,
        imageUrl = accepterInfo.imageUrl,
        title = s"${accepter.firstName} is now collaborating on ${invitedLib.name}",
        body = s"You invited ${accepter.firstName} to join ${invitedLib.name}",
        linkText = s"See ${accepter.firstName}’s profile",
        extraJson = Some(Json.obj(
          "follower" -> accepter,
          "library" -> Json.toJson(invitedLib)
        ))
      )
    }
  }

}

trait LibraryFollowInviteAcceptedImpl extends NonGroupingNotificationKind[LibraryFollowInviteAccepted] {

  override val name: String = "library_follow_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryFollowInviteAccepted.apply, unlift(LibraryFollowInviteAccepted.unapply))

  override def info(event: LibraryFollowInviteAccepted): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(Requests(
      user(event.accepterId), library(event.libraryId)
    )) { subset =>
      val accepterInfo = user(event.accepterId).lookup(subset)
      val accepter = accepterInfo.user
      val acceptedLib = library(event.libraryId).lookup(subset)
      NotificationInfo(
        url = Path(accepter.username.value).encode.absolute,
        imageUrl = accepterInfo.imageUrl,
        title = s"${accepter.firstName} is now following ${acceptedLib.name}",
        body = s"You invited ${accepter.firstName} to join ${acceptedLib.name}",
        linkText = s"See ${accepter.firstName}’s profile",
        extraJson = Some(Json.obj(
          "follower" -> accepter,
          "library" -> Json.toJson(acceptedLib)
        ))
      )
    }
  }

}

trait LibraryNewCollabInviteImpl extends NonGroupingNotificationKind[LibraryNewCollabInvite] {

  override val name: String = "library_new_collab_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryNewCollabInvite.apply, unlift(LibraryNewCollabInvite.unapply))

  override def info(event: LibraryNewCollabInvite): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(Requests(
      user(event.inviterId), library(event.libraryId)
    )) { subset =>
      val inviterInfo = user(event.inviterId).lookup(subset)
      val inviter = inviterInfo.user
      val invitedLibInfo = library(event.libraryId).lookup(subset)
      NotificationInfo(
        url = invitedLibInfo.path.encode.absolute,
        imageUrl = inviterInfo.imageUrl,
        title = s"${inviter.firstName} ${inviter.lastName} invited you to collaborate on a library!",
        body = s"Help ${inviter.firstName} by sharing your knowledge in the library ${invitedLibInfo.name}.",
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

trait LibraryNewFollowInviteImpl extends NonGroupingNotificationKind[LibraryNewFollowInvite] {

  override val name: String = "library_new_follow_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(LibraryNewFollowInvite.apply, unlift(LibraryNewFollowInvite.unapply))

  override def info(event: LibraryNewFollowInvite): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(Requests(
      user(event.inviterId), library(event.libraryId)
    )) { subset =>
      val inviterInfo = user(event.inviterId).lookup(subset)
      val inviter = inviterInfo.user
      val libraryIn = library(event.libraryId).lookup(subset)
      NotificationInfo(
        url = libraryIn.path.encode.absolute,
        imageUrl = inviterInfo.imageUrl,
        title = s"${inviter.firstName} ${inviter.lastName} invited you to follow a library!",
        body = s"Browse keeps in ${libraryIn.name} to find some interesting gems kept by ${libraryIn.name}.", //same
        linkText = "Visit library",
        extraJson = Some(Json.obj(
          "inviter" -> inviter,
          "library" -> Json.toJson(libraryIn),
          "access" -> LibraryAccess.READ_ONLY
        ))
      )
    }
  }

}
