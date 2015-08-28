package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.{ Library, User }
import com.keepit.notify.info._
import com.keepit.notify.model.{ NonGroupingNotificationKind, NotificationKind, Recipient }
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

trait OwnedLibraryNewCollabInviteImpl extends NotificationKind[OwnedLibraryNewCollabInvite] {

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

  private val plurals = Map(
    "someone" -> "some people",
    "some friends" -> "a friend"
  )

  override def info(events: Set[OwnedLibraryNewCollabInvite]): RequestingNotificationInfos[NotificationInfo] = {
    import NotificationInfoRequest._
    def plural(phrase: String) = if (events.size == 1) phrase else plurals(phrase)
    // only need info for a random event, they should all have the same inviter and library
    val oneEvent = events.head
    RequestingNotificationInfos(Requests(
      RequestUser(oneEvent.inviterId), RequestLibrary(oneEvent.libraryId)
    )) { subset =>
      val inviterInfo = RequestUser(oneEvent.inviterId).lookup(subset)
      val inviter = inviterInfo.user
      val libraryInvited = RequestLibrary(oneEvent.libraryId).lookup(subset)
      NotificationInfo(
        url = inviterInfo.path.encode.absolute,
        imageUrl = inviterInfo.imageUrl,
        title = s"${inviter.fullName} invited ${plural("someone")} to conribute to your library!",
        body = s"${inviter.fullName} invited ${plural("some friends")} to contribute to your library, ${libraryInvited.name}",
        linkText = s"See ${inviter.firstName}’s profile", // todo does this make sense?
        extraJson = Some(Json.obj(
          "inviter" -> inviter,
          "library" -> Json.toJson(libraryInvited)
        ))
      )
    }
  }

}

trait OwnedLibraryNewFollowInviteImpl extends NotificationKind[OwnedLibraryNewFollowInvite] {

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

  private val plurals = Map(
    "someone" -> "some people",
    "some friends" -> "a friend"
  )

  override def info(events: Set[OwnedLibraryNewFollowInvite]): RequestingNotificationInfos[NotificationInfo] = {
    import NotificationInfoRequest._
    def plural(phrase: String) = if (events.size == 1) phrase else plurals(phrase)
    // only need info for a random event, they should all have the same inviter and library
    val oneEvent = events.head
    RequestingNotificationInfos(Requests(
      RequestUser(oneEvent.inviterId), RequestLibrary(oneEvent.libraryId)
    )) { subset =>
      val inviterInfo = RequestUser(oneEvent.inviterId).lookup(subset)
      val inviter = inviterInfo.user
      val libraryInvited = RequestLibrary(oneEvent.libraryId).lookup(subset)
      NotificationInfo(
        url = inviterInfo.path.encode.absolute,
        imageUrl = inviterInfo.imageUrl,
        title = s"${inviter.fullName} invited ${plural("someone")} to follow your library!",
        body = s"${inviter.fullName} invited ${plural("some friends")} to follow your library, ${libraryInvited.name}",
        linkText = s"See ${inviter.firstName}’s profile", // todo does this make sense?
        extraJson = Some(Json.obj(
          "inviter" -> inviter,
          "library" -> Json.toJson(libraryInvited)
        ))
      )
    }
  }

}

trait OwnedLibraryNewFollowerImpl extends NonGroupingNotificationKind[OwnedLibraryNewFollower] {

  override val name: String = "owned_library_new_follower"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "followerId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(OwnedLibraryNewFollower.apply, unlift(OwnedLibraryNewFollower.unapply))

  override def info(event: OwnedLibraryNewFollower): RequestingNotificationInfos[NotificationInfo] = {
    import NotificationInfoRequest._
    RequestingNotificationInfos(Requests(
      RequestUser(event.followerId), RequestLibrary(event.libraryId)
    )) { subset =>
      val followerInfo = RequestUser(event.followerId).lookup(subset)
      val follower = followerInfo.user
      val libraryFollowed = RequestLibrary(event.libraryId).lookup(subset)
      NotificationInfo(
        url = followerInfo.path.encode.absolute,
        imageUrl = followerInfo.imageUrl,
        title = "New library follower",
        body = s"${follower.fullName} is now following your library ${libraryFollowed.name}",
        linkText = s"See ${follower.firstName}’s profile",
        extraJson = Some(Json.obj(
          "follower" -> follower,
          "library" -> Json.toJson(libraryFollowed)
        ))
      )
    }
  }

}

trait OwnedLibraryNewCollaboratorImpl extends NonGroupingNotificationKind[OwnedLibraryNewCollaborator] {

  override val name: String = "owned_library_new_collaborator"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "collaboratorId").format[Id[User]] and
    (__ \ "libraryId").format[Id[Library]]
  )(OwnedLibraryNewCollaborator.apply, unlift(OwnedLibraryNewCollaborator.unapply))

  override def info(event: OwnedLibraryNewCollaborator): RequestingNotificationInfos[NotificationInfo] = {
    import NotificationInfoRequest._
    RequestingNotificationInfos(Requests(
      RequestUser(event.collaboratorId), RequestLibrary(event.libraryId)
    )) { subset =>
      val collaboratorInfo = RequestUser(event.collaboratorId).lookup(subset)
      val collaborator = collaboratorInfo.user
      val libraryCollaborating = RequestLibrary(event.libraryId).lookup(subset)
      NotificationInfo(
        url = collaboratorInfo.path.encode.absolute,
        imageUrl = collaboratorInfo.imageUrl,
        title = "New library collaborator",
        body = s"${collaborator.fullName} is now collaborating on your library ${libraryCollaborating.name}",
        linkText = s"See ${collaborator.firstName}’s profile",
        extraJson = Some(Json.obj(
          "follower" -> collaborator, // the mobile clients read it like this
          "library" -> Json.toJson(libraryCollaborating)
        ))
      )
    }
  }

}
