package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.{ Library, User }
import com.keepit.notify.info.{ ExistingDbView, DbViewKey, NotificationInfo, UsingDbView }
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

  def build(recipient: Recipient, time: DateTime, inviter: User, invitee: User, libraryInvited: Library): ExistingDbView[OwnedLibraryNewCollabInvite] = {
    import DbViewKey._
    ExistingDbView(
      user.existing(inviter), user.existing(invitee), library.existing(libraryInvited)
    )(OwnedLibraryNewCollabInvite(
        recipient = recipient,
        time = time,
        inviterId = inviter.id.get,
        inviteeId = invitee.id.get,
        libraryId = libraryInvited.id.get
      ))
  }

  override def shouldGroupWith(newEvent: OwnedLibraryNewCollabInvite, existingEvents: Set[OwnedLibraryNewCollabInvite]): Boolean = {
    // only check a random event, they should all have the same inviter and library
    val existing = existingEvents.head
    existing.inviterId == newEvent.inviterId && existing.libraryId == newEvent.libraryId
  }

  private val plurals = Map(
    "someone" -> "some people",
    "some friends" -> "a friend"
  )

  override def info(events: Set[OwnedLibraryNewCollabInvite]): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    def plural(phrase: String) = if (events.size == 1) phrase else plurals(phrase)
    // only need info for a random event, they should all have the same inviter and library
    val oneEvent = events.head
    UsingDbView(
      user(oneEvent.inviterId), library(oneEvent.libraryId), libraryInfo(oneEvent.libraryId), userImageUrl(oneEvent.inviteeId)
    ) { subset =>
        val inviter = user(oneEvent.inviterId).lookup(subset)
        val libraryInvited = library(oneEvent.libraryId).lookup(subset)
        val libraryInvitedInfo = libraryInfo(oneEvent.libraryId).lookup(subset)
        val inviterImageUrl = userImageUrl(oneEvent.inviterId).lookup(subset)
        NotificationInfo(
          url = Path(inviter.username.value).encode.absolute,
          imageUrl = inviterImageUrl,
          title = s"${inviter.fullName} invited ${plural("someone")} to conribute to your library!",
          body = s"${inviter.fullName} invited ${plural("some friends")} to contribute to your library, ${libraryInvited.name}",
          linkText = s"See ${inviter.firstName}’s profile", // todo does this make sense?
          extraJson = Some(Json.obj(
            "inviter" -> inviter,
            "library" -> Json.toJson(libraryInvitedInfo)
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

  def build(recipient: Recipient, time: DateTime, inviter: User, invitee: User, libraryInvited: Library): ExistingDbView[OwnedLibraryNewFollowInvite] = {
    import DbViewKey._
    ExistingDbView(
      user.existing(inviter), user.existing(invitee), library.existing(libraryInvited)
    )(OwnedLibraryNewFollowInvite(
        recipient = recipient,
        time = time,
        inviterId = inviter.id.get,
        inviteeId = invitee.id.get,
        libraryId = libraryInvited.id.get
      ))
  }

  override def shouldGroupWith(newEvent: OwnedLibraryNewFollowInvite, existingEvents: Set[OwnedLibraryNewFollowInvite]): Boolean = {
    // only check a random event, they should all have the same inviter and library
    val existing = existingEvents.head
    existing.inviterId == newEvent.inviterId && existing.libraryId == newEvent.libraryId
  }

  private val plurals = Map(
    "someone" -> "some people",
    "some friends" -> "a friend"
  )

  override def info(events: Set[OwnedLibraryNewFollowInvite]): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    def plural(phrase: String) = if (events.size == 1) phrase else plurals(phrase)
    // only need info for a random event, they should all have the same inviter and library
    val oneEvent = events.head
    UsingDbView(
      user(oneEvent.inviterId), library(oneEvent.libraryId), libraryInfo(oneEvent.libraryId), userImageUrl(oneEvent.inviteeId)
    ) { subset =>
        val inviter = user(oneEvent.inviterId).lookup(subset)
        val libraryInvited = library(oneEvent.libraryId).lookup(subset)
        val libraryInvitedInfo = libraryInfo(oneEvent.libraryId).lookup(subset)
        val inviterImageUrl = userImageUrl(oneEvent.inviterId).lookup(subset)
        NotificationInfo(
          url = Path(inviter.username.value).encode.absolute,
          imageUrl = inviterImageUrl,
          title = s"${inviter.fullName} invited ${plural("someone")} to follow your library!",
          body = s"${inviter.fullName} invited ${plural("some friends")} to follow your library, ${libraryInvited.name}",
          linkText = s"See ${inviter.firstName}’s profile", // todo does this make sense?
          extraJson = Some(Json.obj(
            "inviter" -> inviter,
            "library" -> Json.toJson(libraryInvitedInfo)
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

  def build(recipient: Recipient, time: DateTime, follower: User, libraryFollowed: Library): ExistingDbView[OwnedLibraryNewFollower] = {
    import DbViewKey._
    ExistingDbView(
      user.existing(follower), library.existing(libraryFollowed)
    )(OwnedLibraryNewFollower(
        recipient = recipient,
        time = time,
        followerId = follower.id.get,
        libraryId = libraryFollowed.id.get
      ))
  }

  override def info(event: OwnedLibraryNewFollower): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(
      user(event.followerId), userImageUrl(event.followerId), library(event.libraryId), libraryInfo(event.libraryId)
    ) { subset =>
        val follower = user(event.followerId).lookup(subset)
        val followerImage = userImageUrl(event.followerId).lookup(subset)
        val libraryFollowed = library(event.libraryId).lookup(subset)
        val libraryFollowedInfo = libraryInfo(event.libraryId).lookup(subset)
        NotificationInfo(
          url = Path(follower.username.value).encode.absolute,
          imageUrl = followerImage,
          title = "New library follower",
          body = s"${follower.fullName} is now following your library ${libraryFollowed.name}",
          linkText = s"See ${follower.firstName}’s profile",
          extraJson = Some(Json.obj(
            "follower" -> BasicUser.fromUser(follower),
            "library" -> Json.toJson(libraryFollowedInfo)
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

  def build(recipient: Recipient, time: DateTime, collaborator: User, libraryCollaborating: Library): ExistingDbView[OwnedLibraryNewCollaborator] = {
    import DbViewKey._
    ExistingDbView(
      user.existing(collaborator), library.existing(libraryCollaborating)
    )(OwnedLibraryNewCollaborator(
        recipient = recipient,
        time = time,
        collaboratorId = collaborator.id.get,
        libraryId = libraryCollaborating.id.get
      ))
  }

  override def info(event: OwnedLibraryNewCollaborator): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(
      user(event.collaboratorId), userImageUrl(event.collaboratorId), library(event.libraryId), libraryInfo(event.libraryId)
    ) { subset =>
        val collaborator = user(event.collaboratorId).lookup(subset)
        val collaboratorImage = userImageUrl(event.collaboratorId).lookup(subset)
        val libraryCollaborating = library(event.libraryId).lookup(subset)
        val libraryCollaboratingInfo = libraryInfo(event.libraryId).lookup(subset)
        NotificationInfo(
          url = Path(collaborator.username.value).encode.absolute,
          imageUrl = collaboratorImage,
          title = "New library collaborator",
          body = s"${collaborator.fullName} is now collaborating on your library ${libraryCollaborating.name}",
          linkText = s"See ${collaborator.firstName}’s profile",
          extraJson = Some(Json.obj(
            "follower" -> BasicUser.fromUser(collaborator), // the mobile clients read it like this
            "library" -> Json.toJson(libraryCollaboratingInfo)
          ))
        )
      }
  }

}
