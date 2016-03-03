package com.keepit.notify.info

import com.google.inject.{Inject, Singleton}
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.path.Path
import com.keepit.eliza.model.{MessageThread, Notification, NotificationItem}
import com.keepit.model.{LibraryPermission, SourceAttribution, SlackAttribution, Keep, LibraryAccess, NotificationCategory}
import com.keepit.notify.info.NotificationInfoRequest._
import com.keepit.notify.model.event._
import com.keepit.social.ImageUrls
import play.api.libs.json.Json

@Singleton
class NotificationKindInfoRequests @Inject()(implicit val pubIdConfig: PublicIdConfiguration) {
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
      case LibraryCollabInviteAccepted => genericInfoFn(infoForLibraryCollabInviteAccepted)
      case LibraryFollowInviteAccepted => genericInfoFn(infoForLibraryFollowInviteAccepted)
      case LibraryNewCollabInvite => genericInfoFn(infoForLibraryNewCollabInvite)
      case LibraryNewFollowInvite => genericInfoFn(infoForLibraryNewFollowInvite)
      case DepressedRobotGrumble => genericInfoFn(infoForDepressedRobotGrumble)
      case OrgNewInvite => genericInfoFn(infoForOrgNewInvite)
      case OrgInviteAccepted => genericInfoFn(infoForOrgInviteAccepted)
      case OrgMemberJoined => genericInfoFn(infoForOrgMemberJoined)
      case RewardCreditApplied => genericInfoFn(infoForRewardCreditApplied)
      case OwnedLibraryNewCollabInvite => genericInfoFn(infoForOwnedLibraryNewCollabInvite)
      case OwnedLibraryNewFollowInvite => genericInfoFn(infoForOwnedLibraryNewFollowInvite)
      case OwnedLibraryNewFollower => genericInfoFn(infoForOwnedLibraryNewFollower)
      case OwnedLibraryNewCollaborator => genericInfoFn(infoForOwnedLibraryNewCollaborator)
      case NewSocialConnection => genericInfoFn(infoForNewSocialConection)
      case SocialContactJoined => genericInfoFn(infoForSocialContactJoined)
      case LegacyNotification => genericInfoFn(infoForLegacyNotification)
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
        locator = None,
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
        locator = None,
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
      val libraryKept = RequestLibrary(event.libraryId).lookup(batched)
      val author = newKeep.author
      val slackAttributionOpt = newKeep.attribution

      val body = {


        slackAttributionOpt.map { attr =>
          val titleString = newKeep.title.map(title => s": $title") getOrElse("")
          attr.message.channel.name match {
            case Some(prettyChannelName) => s"${author.name} just added in #$prettyChannelName" + titleString
            case None => s"${author.name} just shared" + titleString
          }
        }.getOrElse(s"${author.name} just kept ${newKeep.title.getOrElse("a new keep")}")
      }

      val locator = if (libraryKept.permissions.contains(LibraryPermission.ADD_COMMENTS)) Some(MessageThread.locator(Keep.publicId(event.keepId))) else None // don't deep link in ext if user can't comment

      import com.keepit.common._
      StandardNotificationInfo(
        url = newKeep.url,
        image = PublicImage(author.picture),
        title = s"New keep in ${libraryKept.name}",
        body = body,
        linkText = "Go to page",
        locator = locator,
        extraJson = Some(Json.obj(
          "keeper" -> author,
          "library" -> Json.toJson(libraryKept),
          "keep" -> Json.obj(
            "id" -> newKeep.id,
            "url" -> newKeep.url,
            "attr" -> newKeep.attribution
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
        locator = None,
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
        locator = None,
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
        url = Path(invitedLib.path).absolute,
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
        url = Path(invitedLib.path).absolute,
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
        url = Path(invitedOrg.handle.value).absolute,
        image = UserImage(inviter),
        title = s"${inviter.firstName} ${inviter.lastName} invited you to join ${invitedOrg.abbreviatedName}!",
        body = s"Help ${invitedOrg.abbreviatedName} by sharing your knowledge with them.",
        linkText = "Visit team",
        extraJson = Some(Json.obj(
          "organization" -> Json.toJson(invitedOrg)
        )),
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
        url = Path(acceptedOrg.handle.value).absolute,
        image = UserImage(accepter),
        title = s"${accepter.firstName} accepted your invitation to join ${acceptedOrg.abbreviatedName}!",
        body = s"You invited ${accepter.firstName} to join ${acceptedOrg.abbreviatedName}",
        linkText = "Visit team",
        extraJson = Some(Json.obj(
          "member" -> accepter,
          "organization" -> Json.toJson(acceptedOrg)
        )),
        category = NotificationCategory.User.ORGANIZATION_JOINED
      )
    }
  }

  def infoForOrgMemberJoined(events: Set[OrgMemberJoined]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestUser(event.memberId), RequestOrganization(event.orgId)
    )) { batched =>
      val member = RequestUser(event.memberId).lookup(batched)
      val acceptedOrg = RequestOrganization(event.orgId).lookup(batched)
      StandardNotificationInfo(
        url = Path(acceptedOrg.handle.value).absolute,
        image = UserImage(member),
        title = s"${member.firstName} joined the ${acceptedOrg.abbreviatedName} team!",
        body = s"You can now share knowledge with ${member.firstName} on the ${acceptedOrg.abbreviatedName} team",
        linkText = "View team members",
        extraJson = Some(Json.obj(
          "member" -> member,
          "organization" -> Json.toJson(acceptedOrg)
        )),
        category = NotificationCategory.User.ORGANIZATION_JOINED
      )
    }
  }

  def infoForRewardCreditApplied(events: Set[RewardCreditApplied]): RequestingNotificationInfos[StandardNotificationInfo] = {
    val event = requireOne(events)
    RequestingNotificationInfos(Requests(
      RequestOrganization(event.orgId)
    )) { batched =>
      val org = RequestOrganization(event.orgId).lookup(batched)
      StandardNotificationInfo(
        url = Path(org.handle.value + "/settings/credits#rewards").absolute,
        image = OrganizationImage(org),
        title = s"Reward credit applied to ${org.abbreviatedName}",
        body = event.description,
        linkText = "View details",
        extraJson = Some(Json.obj(
          "organization" -> Json.toJson(org)
        )),
        category = NotificationCategory.User.REWARD_CREDIT_APPLIED
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
        title = s"${inviter.fullName} invited ${plural("someone")} to contribute to your library!",
        body = s"${inviter.fullName} invited ${plural("someone")} to contribute to your library, ${libraryInvited.name}",
        linkText = s"See ${inviter.firstName}’s profile", // todo does this make sense?
        locator = None,
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
        locator = None,
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
        locator = None,
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
        locator = None,
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
        locator = None,
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
        locator = None,
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
}
