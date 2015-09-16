package com.keepit.notify.info

import com.google.inject.{Inject, Singleton}
import com.keepit.common.path.Path
import com.keepit.eliza.model.{Notification, NotificationItem}
import com.keepit.model.{NotificationCategory, LibraryAccess}
import com.keepit.notify.model.NotificationKind
import com.keepit.notify.model.event._
import play.api.libs.json.Json
import NotificationInfoRequest._

@Singleton
class NotificationKindInfoRequests @Inject() () {

  private def genericInfoFn[N <: NotificationEvent](
    fn: Set[N] => RequestingNotificationInfos[NotificationInfo]
  ): Set[NotificationEvent] => RequestingNotificationInfos[NotificationInfo] = {
    // the function does not know that the items it has have the same kind
    fn match {
      case fnGeneric: (Set[NotificationEvent]  => RequestingNotificationInfos[NotificationInfo]) @unchecked => fnGeneric
      case _ => throw new IllegalArgumentException(s"Somehow was unable to find a generic notification info fn for $fn")
    }
  }

  def requireOne[N <: NotificationEvent](events: Set[N]): N = {
    require(events.size == 1,
      "This kind is supposed to guarantee that no events ever group, yet a group of events was received.")
    events.head
  }

  def requestsFor(notif: Notification, items: Set[NotificationItem]): RequestingNotificationInfos[NotificationInfo] = {
    val infoFn = notif.kind match {
      case NewConnectionInvite => genericInfoFn(infoForNewConnectionInvite)
      case ConnectionInviteAccepted => genericInfoFn(infoForConnectionInviteAccepted)
      case LibraryNewKeep => genericInfoFn(infoForLibraryNewKeep)
      case NewKeepActivity => genericInfoFn(infoForNewKeepActivity)
      case LibraryCollabInviteAccepted => genericInfoFn(infoForLibraryCollabInviteAccepted)
      case LibraryFollowInviteAccepted => genericInfoFn(infoForLibraryFollowInviteAccepted)
      case LibraryNewCollabInvite => genericInfoFn(infoForLibraryNewCollabInvite)
      case LibraryNewFollowInvite => genericInfoFn(infoForLibraryNewFollowInvite)
      case DepressedRobotGrumble => genericInfoFn(infoForDepressedRobotGrumble)
      case OrgNewInvite => genericInfoFn(infoForOrgNewInvite)
      case OrgInviteAccepted => genericInfoFn(infoForOrgNewInvite)
      case OwnedLibraryNewCollabInvite => genericInfoFn(infoForOwnedLibraryNewCollabInvite)
      case OwnedLibraryNewFollowInvite => genericInfoFn(infoForOwnedLibraryNewFollowInvite)
      case OwnedLibraryNewFollower => genericInfoFn(infoForOwnedLibraryNewFollower)
      case OwnedLibraryNewCollaborator => genericInfoFn(infoForOwnedLibraryNewCollaborator)
      case NewSocialConnection => genericInfoFn(infoForNewSocialConection)
      case SocialContactJoined => genericInfoFn(infoForSocialContactJoined)
      case LegacyNotification => genericInfoFn(infoForLegacyNotification)
      case NewMessage => genericInfoFn(infoForNewMessage)
    }
    infoFn(items.map(_.event))
  }

  def infoForNewConnectionInvite(events: Set[NewConnectionInvite]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestUser(event.inviterId)
    )) { batched =>
      val inviter = RequestUser(event.inviterId).lookup(batched)
      StandardNotificationInfo(
        user = inviter,
        title = s"${inviter.firstName} ${inviter.lastName} wants to connect with you on Kifi",
        body = s"Enjoy ${inviter.firstName}’s keeps in your search results and message ${inviter.firstName} directly.",
        linkText = s"Respond to ${inviter.firstName}’s invitation",
        extraJson = Some(Json.obj(
          "friend" -> inviter
        )),
        category = NotificationCategory.User.FRIEND_REQUEST
      )
    }
  }

  def infoForConnectionInviteAccepted(events: Set[ConnectionInviteAccepted]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestUser(event.accepterId)
    )) { batched =>
      val accepter = RequestUser(event.accepterId).lookup(batched)
      StandardNotificationInfo(
        user = accepter,
        title = s"${accepter.firstName} ${accepter.lastName} accepted your invitation to connect!",
        body = s"Now you will enjoy ${accepter.firstName}’s keeps in your search results and message ${accepter.firstName} directly.",
        linkText = s"Visit ${accepter.firstName}’s profile",
        extraJson = Some(Json.obj(
          "friend" -> accepter
        )),
        category = NotificationCategory.User.FRIEND_ACCEPTED
      )
    }
  }

  def infoForLibraryNewKeep(events: Set[LibraryNewKeep]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestLibrary(event.libraryId), RequestKeep(event.keepId)
    )) { batched =>
      val newKeep = RequestKeep(event.keepId).lookup(batched)
      val keeper = RequestUserExternal(newKeep.ownerId).lookup(batched)
      val libraryKept = RequestLibrary(event.libraryId).lookup(batched)
      StandardNotificationInfo(
        url = newKeep.url,
        image = UserImage(keeper),
        title = s"New keep in ${libraryKept.name}",
        body = s"${keeper.firstName} has just kept ${newKeep.title.getOrElse("a new item")}",
        linkText = "Go to page",
        extraJson = Some(Json.obj(
          "keeper" -> keeper,
          "library" -> Json.toJson(libraryKept),
          "keep" -> Json.obj(
            "id" -> newKeep.id,
            "url" -> newKeep.url
          )
        )),
        category = NotificationCategory.User.NEW_KEEP
      )
    }
  }

  def infoForNewKeepActivity(events: Set[NewKeepActivity]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestLibrary(event.libraryId), RequestKeep(event.keepId)
    )) { batched =>
      val libraryKept = RequestLibrary(event.libraryId).lookup(batched)
      val newKeep = RequestKeep(event.keepId).lookup(batched)
      val keeper = RequestUserExternal(newKeep.ownerId).lookup(batched)
      StandardNotificationInfo(
        url = libraryKept.url.encode.absolute,
        image = UserImage(keeper),
        title = s"New Keep in ${libraryKept.name}",
        body = s"${keeper.firstName} has just kept ${newKeep.title.getOrElse("a new item")}",
        linkText = "Go to library",
        extraJson = Some(Json.obj(
          "keeper" -> keeper,
          "library" -> Json.toJson(libraryKept),
          "keep" -> Json.obj(
            "id" -> newKeep.id,
            "url" -> newKeep.url
          )
        )),
        category = NotificationCategory.User.NEW_KEEP
      )
    }
  }

  def infoForLibraryCollabInviteAccepted(events: Set[LibraryCollabInviteAccepted]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestUser(event.accepterId), RequestLibrary(event.libraryId)
    )) { batched =>
      val accepter = RequestUser(event.accepterId).lookup(batched)
      val invitedLib = RequestLibrary(event.libraryId).lookup(batched)
      StandardNotificationInfo(
        user = accepter,
        title = s"${accepter.firstName} is now collaborating on ${invitedLib.name}",
        body = s"You invited ${accepter.firstName} to join ${invitedLib.name}",
        linkText = s"See ${accepter.firstName}’s profile",
        extraJson = Some(Json.obj(
          "follower" -> accepter,
          "library" -> Json.toJson(invitedLib)
        )),
        category = NotificationCategory.User.LIBRARY_FOLLOWED
      )
    }
  }

  def infoForLibraryFollowInviteAccepted(events: Set[LibraryFollowInviteAccepted]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestUser(event.accepterId), RequestLibrary(event.libraryId)
    )) { batched =>
      val accepter = RequestUser(event.accepterId).lookup(batched)
      val acceptedLib = RequestLibrary(event.libraryId).lookup(batched)
      StandardNotificationInfo(
        user = accepter,
        title = s"${accepter.firstName} is now following ${acceptedLib.name}",
        body = s"You invited ${accepter.firstName} to join ${acceptedLib.name}",
        linkText = s"See ${accepter.firstName}’s profile",
        extraJson = Some(Json.obj(
          "follower" -> accepter,
          "library" -> Json.toJson(acceptedLib)
        )),
        category = NotificationCategory.User.LIBRARY_FOLLOWED
      )
    }
  }

  def infoForLibraryNewCollabInvite(events: Set[LibraryNewCollabInvite]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestUser(event.inviterId), RequestLibrary(event.libraryId)
    )) { batched =>
      val inviter = RequestUser(event.inviterId).lookup(batched)
      val invitedLib = RequestLibrary(event.libraryId).lookup(batched)
      StandardNotificationInfo(
        url = invitedLib.url.encode.absolute,
        image = UserImage(inviter),
        title = s"${inviter.firstName} ${inviter.lastName} invited you to collaborate on a library!",
        body = s"Help ${inviter.firstName} by sharing your knowledge in the library ${invitedLib.name}.",
        linkText = "Visit library",
        extraJson = Some(Json.obj(
          "inviter" -> inviter,
          "library" -> Json.toJson(invitedLib),
          "access" -> LibraryAccess.READ_WRITE
        )),
        category = NotificationCategory.User.LIBRARY_INVITATION
      )
    }
  }

  def infoForLibraryNewFollowInvite(events: Set[LibraryNewFollowInvite]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestUser(event.inviterId), RequestLibrary(event.libraryId)
    )) { batched =>
      val inviter = RequestUser(event.inviterId).lookup(batched)
      val invitedLib = RequestLibrary(event.libraryId).lookup(batched)
      StandardNotificationInfo(
        url = invitedLib.url.encode.absolute,
        image = UserImage(inviter),
        title = s"${inviter.firstName} ${inviter.lastName} invited you to follow a library!",
        body = s"Browse keeps in ${invitedLib.name} to find some interesting gems kept by ${inviter.firstName}.", //same
        linkText = "Visit library",
        extraJson = Some(Json.obj(
          "inviter" -> inviter,
          "library" -> Json.toJson(invitedLib),
          "access" -> LibraryAccess.READ_ONLY
        )),
        category = NotificationCategory.User.LIBRARY_INVITATION
      )
    }
  }

  def infoForDepressedRobotGrumble(events: Set[DepressedRobotGrumble]): RequestingNotificationInfos[StandardNotificationInfo] = {
    def plural(phrase: String) = if (events.size == 1) phrase else NotificationEnglish.plurals(phrase)

    RequestingNotificationInfos(Requests()) { batched =>
      StandardNotificationInfo(
        url = "http://goo.gl/PqN7Cs",
        image = PublicImage("http://i.imgur.com/qs8QofA.png"),
        title = s"${plural("A robot")} just grumbled! ${plural("He")} must be depressed...",
        body = s"${NotificationEnglish.englishJoin(events.toSeq.map(_.robotName))} just grumbled about ${NotificationEnglish.englishJoin(events.toSeq.map(_.grumblingAbout))}",
        linkText = "Organize and share knowledge with Kifi!",
        category = NotificationCategory.System.ADMIN
      )
    }
  }

  def infoForOrgNewInvite(events: Set[OrgNewInvite]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestUser(event.inviterId), RequestOrganization(event.orgId)
    )) { batched =>
      val inviter = RequestUser(event.inviterId).lookup(batched)
      val invitedOrg = RequestOrganization(event.orgId).lookup(batched)
      StandardNotificationInfo(
        url = Path(invitedOrg.handle.value).encode.absolute,
        image = UserImage(inviter),
        title = s"${inviter.firstName} ${inviter.lastName} invited you to join ${invitedOrg.abbreviatedName}!",
        body = s"Help ${invitedOrg.abbreviatedName} by sharing your knowledge with them.",
        linkText = "Visit organization",
        category = NotificationCategory.User.ORGANIZATION_INVITATION
      )
    }
  }

  def infoForOrgInviteAccepted(events: Set[OrgInviteAccepted]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestUser(event.accepterId), RequestOrganization(event.orgId)
    )) { batched =>
      val accepter = RequestUser(event.accepterId).lookup(batched)
      val acceptedOrg = RequestOrganization(event.orgId).lookup(batched)
      StandardNotificationInfo(
        url = Path(acceptedOrg.handle.value).encode.absolute,
        image = UserImage(accepter),
        title = s"${accepter.firstName} accepted your invitation to join ${acceptedOrg.abbreviatedName}!",
        body = s"You invited ${accepter.firstName} to join ${acceptedOrg.abbreviatedName}",
        linkText = "Visit organization",
        extraJson = Some(Json.obj(
          "member" -> accepter,
          "organization" -> Json.toJson(acceptedOrg)
        )),
        category = NotificationCategory.User.ORGANIZATION_JOINED
      )
    }
  }

  def infoForOwnedLibraryNewCollabInvite(events: Set[OwnedLibraryNewCollabInvite]): RequestingNotificationInfos[StandardNotificationInfo] = {
    def plural(phrase: String) = if (events.size == 1) phrase else NotificationEnglish.plurals(phrase)
    // only need info for a random event, they should all have the same inviter and library
    val oneEvent = events.head
    RequestingNotificationInfos(Requests(
      RequestUser(oneEvent.inviterId), RequestLibrary(oneEvent.libraryId)
    )) { batched =>
      val inviter = RequestUser(oneEvent.inviterId).lookup(batched)
      val libraryInvited = RequestLibrary(oneEvent.libraryId).lookup(batched)
      StandardNotificationInfo(
        user = inviter,
        title = s"${inviter.fullName} invited ${plural("someone")} to conribute to your library!",
        body = s"${inviter.fullName} invited ${plural("some friends")} to contribute to your library, ${libraryInvited.name}",
        linkText = s"See ${inviter.firstName}’s profile", // todo does this make sense?
        extraJson = Some(Json.obj(
          "inviter" -> inviter,
          "library" -> Json.toJson(libraryInvited)
        )),
        category = NotificationCategory.User.LIBRARY_FOLLOWED
      )
    }
  }

  def infoForOwnedLibraryNewFollowInvite(events: Set[OwnedLibraryNewFollowInvite]): RequestingNotificationInfos[StandardNotificationInfo] = {
    def plural(phrase: String) = if (events.size == 1) phrase else NotificationEnglish.plurals(phrase)
    // only need info for a random event, they should all have the same inviter and library
    val oneEvent = events.head
    RequestingNotificationInfos(Requests(
      RequestUser(oneEvent.inviterId), RequestLibrary(oneEvent.libraryId)
    )) { batched =>
      val inviter = RequestUser(oneEvent.inviterId).lookup(batched)
      val libraryInvited = RequestLibrary(oneEvent.libraryId).lookup(batched)
      StandardNotificationInfo(
        user = inviter,
        title = s"${inviter.fullName} invited ${plural("someone")} to follow your library!",
        body = s"${inviter.fullName} invited ${plural("some friends")} to follow your library, ${libraryInvited.name}",
        linkText = s"See ${inviter.firstName}’s profile", // todo does this make sense?
        extraJson = Some(Json.obj(
          "inviter" -> inviter,
          "library" -> Json.toJson(libraryInvited)
        )),
        category = NotificationCategory.User.LIBRARY_FOLLOWED
      )
    }
  }

  def infoForOwnedLibraryNewFollower(events: Set[OwnedLibraryNewFollower]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestUser(event.followerId), RequestLibrary(event.libraryId)
    )) { batched =>
      val follower = RequestUser(event.followerId).lookup(batched)
      val libraryFollowed = RequestLibrary(event.libraryId).lookup(batched)
      StandardNotificationInfo(
        user = follower,
        title = "New library follower",
        body = s"${follower.fullName} is now following your library ${libraryFollowed.name}",
        linkText = s"See ${follower.firstName}’s profile",
        extraJson = Some(Json.obj(
          "follower" -> follower,
          "library" -> Json.toJson(libraryFollowed)
        )),
        category = NotificationCategory.User.LIBRARY_FOLLOWED
      )
    }
  }

  def infoForOwnedLibraryNewCollaborator(events: Set[OwnedLibraryNewCollaborator]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestUser(event.collaboratorId), RequestLibrary(event.libraryId)
    )) { batched =>
      val collaborator = RequestUser(event.collaboratorId).lookup(batched)
      val libraryCollaborating = RequestLibrary(event.libraryId).lookup(batched)
      StandardNotificationInfo(
        user = collaborator,
        title = "New library collaborator",
        body = s"${collaborator.fullName} is now collaborating on your library ${libraryCollaborating.name}",
        linkText = s"See ${collaborator.firstName}’s profile",
        extraJson = Some(Json.obj(
          "follower" -> collaborator, // the mobile clients read it like this
          "library" -> Json.toJson(libraryCollaborating)
        )),
        category = NotificationCategory.User.LIBRARY_FOLLOWED
      )
    }
  }

  def infoForNewSocialConection(events: Set[NewSocialConnection]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestUser(event.friendId)
    )) { batched =>
      val friend = RequestUser(event.friendId).lookup(batched)
      StandardNotificationInfo(
        user = friend,
        title = s"You’re connected with ${friend.fullName} on Kifi!",
        body = s"Enjoy ${friend.firstName}’s keeps in your search results and message ${friend.firstName} directly",
        linkText = s"View ${friend.firstName}’s profile",
        extraJson = Some(Json.obj(
          "friend" -> friend
        )),
        category = NotificationCategory.User.SOCIAL_FRIEND_JOINED
      )
    }
  }

  def infoForSocialContactJoined(events: Set[SocialContactJoined]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestUser(event.joinerId)
    )) { batched =>
      val joiner = RequestUser(event.joinerId).lookup(batched)
      StandardNotificationInfo(
        user = joiner,
        title = s"${joiner.firstName} ${joiner.lastName} joined Kifi!",
        body = s"To discover ${joiner.firstName}’s public keeps while searching, get connected! Invite ${joiner.firstName} to connect on Kifi »",
        linkText = s"Invite ${joiner.firstName} to connect",
        extraJson = None,
        category = NotificationCategory.User.CONTACT_JOINED
      )
    }
  }

  def infoForLegacyNotification(events: Set[LegacyNotification]): RequestingNotificationInfos[LegacyNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests()) { batched =>
      LegacyNotificationInfo(
        json = event.json
      )
    }
  }

  def infoForNewMessage(events: Set[NewMessage]): RequestingNotificationInfos[MessageNotificationInfo] = {
    RequestingNotificationInfos(Requests()) { batched =>
      MessageNotificationInfo(
        events
      )
    }
  }

}
