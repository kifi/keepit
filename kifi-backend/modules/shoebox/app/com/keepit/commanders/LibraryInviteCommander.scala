package com.keepit.commanders

import com.google.inject.{ Inject, Provider }
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.RichContact
import com.keepit.commanders.emails.LibraryInviteEmailSender
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ BasicContact, ElectronicMail, EmailAddress }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.eliza.{ ElizaServiceClient, LibraryPushNotificationCategory, PushNotificationExperiment, UserPushNotificationCategory }
import com.keepit.heimdal.{ HeimdalContext, HeimdalServiceClient }
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.http.Status._
import play.api.libs.json.Json
import scala.util.Try

import scala.concurrent.{ ExecutionContext, Future }

class LibraryInviteCommander @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryInviteRepo: LibraryInviteRepo,
    libraryInvitesAbuseMonitor: LibraryInvitesAbuseMonitor,
    userRepo: UserRepo,
    basicUserRepo: BasicUserRepo,
    s3ImageStore: S3ImageStore,
    elizaClient: ElizaServiceClient,
    abookClient: ABookServiceClient,
    libraryAnalytics: LibraryAnalytics,
    libraryInviteSender: Provider[LibraryInviteEmailSender],
    heimdal: HeimdalServiceClient,
    libraryImageCommander: LibraryImageCommander,
    kifiInstallationCommander: KifiInstallationCommander,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) extends Logging {

  def convertPendingInvites(emailAddress: EmailAddress, userId: Id[User]): Unit = {
    db.readWrite { implicit s =>
      libraryInviteRepo.getByEmailAddress(emailAddress, Set.empty) foreach { libInv =>
        libraryInviteRepo.save(libInv.copy(userId = Some(userId)))
      }
    }
  }

  def declineLibrary(userId: Id[User], libraryId: Id[Library]) = {
    db.readWrite { implicit s =>
      val listInvites = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId = libraryId, userId = userId)
      listInvites.map(inv => libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.DECLINED)))
    }
  }

  def notifyInviterOnLibraryInvitationAcceptance(invitesToAlert: Seq[LibraryInvite], invitee: User, lib: Library, owner: BasicUser): Unit = {
    val invaiteeImage = s3ImageStore.avatarUrlByUser(invitee)
    val libImageOpt = libraryImageCommander.getBestImageForLibrary(lib.id.get, ProcessedImageSize.Medium.idealSize)
    invitesToAlert foreach { invite =>
      val title = if (invite.access == LibraryAccess.READ_WRITE) {
        s"${invitee.firstName} is now collaborating on ${lib.name}"
      } else {
        s"${invitee.firstName} is now following ${lib.name}"
      }
      val inviterId = invite.inviterId
      elizaClient.sendGlobalNotification( //push sent
        userIds = Set(inviterId),
        title = title,
        body = s"You invited ${invitee.fullName} to join ${lib.name}.",
        linkText = s"See ${invitee.firstName}’s profile",
        linkUrl = s"https://www.kifi.com/${invitee.username.value}",
        imageUrl = invaiteeImage,
        sticky = false,
        category = NotificationCategory.User.LIBRARY_FOLLOWED,
        extra = Some(Json.obj(
          "follower" -> BasicUser.fromUser(invitee),
          "library" -> Json.toJson(LibraryNotificationInfo.fromLibraryAndOwner(lib, libImageOpt, owner))
        ))
      ) map { _ =>
          val canSendPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(inviterId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
          if (canSendPush) {
            elizaClient.sendUserPushNotification(
              userId = inviterId,
              message = title,
              recipient = invitee,
              pushNotificationExperiment = PushNotificationExperiment.Experiment1,
              category = UserPushNotificationCategory.NewLibraryFollower)
          }
        }
    }
  }

  def inviteAnonymousToLibrary(libraryId: Id[Library], inviterId: Id[User], access: LibraryAccess, message: Option[String])(implicit context: HeimdalContext): Either[LibraryFail, (LibraryInvite, Library)] = {
    val (library, inviterMembershipOpt) = db.readOnlyMaster { implicit s =>
      val library = libraryRepo.get(libraryId)
      val membership = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, inviterId)
      (library, membership)
    }

    val badInvite = improperInvite(library, inviterMembershipOpt, access)
    if (library.kind == LibraryKind.SYSTEM_MAIN || library.kind == LibraryKind.SYSTEM_SECRET) {
      Left(LibraryFail(BAD_REQUEST, "cant_invite_to_system_generated_library"))
    } else if (badInvite.isDefined) {
      log.warn(s"[inviteAnonymousToLibrary] error: user $inviterId attempting to generate link invite for $access access to library (${library.id.get}, ${library.name}, ${library.visibility}, ${library.whoCanInvite})")
      Left(badInvite.get)
    } else {
      val libInvite = db.readWrite { implicit s =>
        libraryInviteRepo.save(LibraryInvite(libraryId = libraryId, inviterId = inviterId, userId = None, emailAddress = None, access = access, message = message))
      }
      Right((libInvite, library))
    }
  }

  def inviteToLibrary(libraryId: Id[Library], inviterId: Id[User], inviteList: Seq[(Either[Id[User], EmailAddress], LibraryAccess, Option[String])])(implicit eventContext: HeimdalContext): Future[Either[LibraryFail, Seq[(Either[BasicUser, RichContact], LibraryAccess)]]] = {
    val (lib, inviterMembership) = db.readOnlyMaster { implicit s =>
      val lib = libraryRepo.get(libraryId)
      val mem = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, inviterId)
      (lib, mem)
    }

    if (lib.kind == LibraryKind.SYSTEM_MAIN || lib.kind == LibraryKind.SYSTEM_SECRET) {
      Future.successful(Left(LibraryFail(BAD_REQUEST, "cant_invite_to_system_generated_library")))
    } else {
      // get all invitee contacts by email address
      val futureInviteeContactsByEmailAddress = {
        val invitedEmailAddresses = inviteList.collect { case (Right(emailAddress), _, _) => emailAddress }
        abookClient.internKifiContacts(inviterId, invitedEmailAddresses.map(BasicContact(_)): _*).imap { kifiContacts =>
          (invitedEmailAddresses zip kifiContacts).toMap
        }
      }

      // get all invitee users (mapped userId -> basicuser)
      val inviteeUserMap = {
        val invitedUserIds = inviteList.collect { case (Left(userId), _, _) => userId }
        db.readOnlyMaster { implicit s =>
          basicUserRepo.loadAll(invitedUserIds.toSet)
        }
      }

      futureInviteeContactsByEmailAddress.map { inviteeContactsByEmailAddress => // when email contacts are done fetching... process through inviteList
        val invitesForInvitees = {
          val libMembersMap = db.readOnlyMaster { implicit s =>
            libraryMembershipRepo.getWithLibraryId(lib.id.get)
          }.map { mem =>
            mem.userId -> mem
          }.toMap

          for ((recipient, inviteAccess, msgOpt) <- inviteList) yield {
            improperInvite(lib, inviterMembership, inviteAccess) match {
              case Some(fail) =>
                log.warn(s"[inviteUsersToLibrary] error: user $inviterId attempting to invite $recipient for $inviteAccess access to library (${lib.id.get}, ${lib.name}, ${lib.visibility}, ${lib.whoCanInvite})")
                None
              case _ =>
                recipient match {
                  case Left(userId) =>
                    libMembersMap.get(userId) match {
                      case Some(mem) if mem.isOwner || mem.isCollaborator => // don't persist invite to an owner or collaborator
                        log.warn(s"[inviteUsersToLibrary] not persisting invite: user $inviterId attempting to invite user $userId when recipient is already owner or collaborator")
                        None
                      case Some(mem) if mem.access == inviteAccess => // don't persist invite to a user with same access
                        log.warn(s"[inviteUsersToLibrary] not persisting invite: user $inviterId attempting to invite user $userId for $inviteAccess access when membership has same access level")
                        None
                      case Some(mem) if inviteAccess == LibraryAccess.READ_WRITE && mem.state == LibraryMembershipStates.ACTIVE => // auto-promote people when they're already in the library
                        db.readWrite { implicit session =>
                          libraryMembershipRepo.save(mem.copy(access = inviteAccess)) // Not a huge fan of this location, but the persisting function isn't better.
                        }
                        val newInvite = LibraryInvite(libraryId = libraryId, inviterId = inviterId, userId = Some(userId), access = inviteAccess, message = msgOpt, state = LibraryInviteStates.ACCEPTED)
                        val inviteeInfo = (Left(inviteeUserMap(userId)), inviteAccess)
                        Some((newInvite, inviteeInfo))
                      case _ =>
                        val newInvite = LibraryInvite(libraryId = libraryId, inviterId = inviterId, userId = Some(userId), access = inviteAccess, message = msgOpt)
                        val inviteeInfo = (Left(inviteeUserMap(userId)), inviteAccess)
                        Some((newInvite, inviteeInfo))
                    }
                  case Right(email) =>
                    val newInvite = LibraryInvite(libraryId = libraryId, inviterId = inviterId, emailAddress = Some(email), access = inviteAccess, message = msgOpt)
                    val inviteeInfo = (Right(inviteeContactsByEmailAddress(email)), inviteAccess)
                    Some((newInvite, inviteeInfo))
                }
            }
          }
        }
        val (invites, inviteesWithAccess) = invitesForInvitees.flatten.unzip
        libraryAnalytics.sendLibraryInvite(inviterId, lib, inviteList.map(_._1), eventContext)
        persistInvitesAndNotify(invites)
        Right(inviteesWithAccess)
      }
    }
  }

  def improperInvite(library: Library, inviterMembership: Option[LibraryMembership], access: LibraryAccess): Option[LibraryFail] = {
    val inviterIsOwner = inviterMembership.exists(_.isOwner)
    val inviterIsCollab = inviterMembership.exists(_.isCollaborator)
    val collabCannotInvite = library.whoCanInvite.contains(LibraryInvitePermissions.OWNER)
    if (access == LibraryAccess.READ_WRITE && !inviterIsOwner && !inviterIsCollab) {
      // invite to RW, but inviter is not owner or collaborator
      Some(LibraryFail(BAD_REQUEST, "cant_invite_rw_nonowner_noncollab"))
    } else if (access == LibraryAccess.READ_WRITE && inviterIsCollab && collabCannotInvite) {
      // invite to RW, but inviter is collaborator but library does not allow
      Some(LibraryFail(BAD_REQUEST, "cant_invite_rw_noncollablib"))
    } else if (access == LibraryAccess.READ_ONLY && library.isSecret && !inviterIsOwner && !inviterIsCollab) {
      // invite is RO, but library is secret & inviter is not owner or collaborator
      Some(LibraryFail(BAD_REQUEST, "cant_invite_ro_secretlib__nonowner_noncollab"))
    } else if (access == LibraryAccess.READ_ONLY && library.isSecret && inviterIsCollab && collabCannotInvite) {
      // invite is RO, but library is secret & inviter is collaborator but library does not allow
      Some(LibraryFail(BAD_REQUEST, "cant_invite_ro_secretlib_noncollablib"))
    } else {
      None
    }
  }

  def persistInvitesAndNotify(invites: Seq[LibraryInvite]): Future[Seq[ElectronicMail]] = {
    val groupedInvitesWithExtras = invites.groupBy(invite => (invite.inviterId, invite.libraryId, invite.userId, invite.emailAddress)).map {
      case ((inviterId, libId, recipientId, recipientEmail), inviteGroup) =>
        val (inviter, lib, libOwner, lastInviteOpt) = db.readOnlyMaster { implicit s =>
          val inviter = userRepo.get(inviterId)
          val lib = libraryRepo.get(libId)
          val libOwner = basicUserRepo.load(lib.ownerId)
          val lastInviteOpt = (recipientId, recipientEmail) match {
            case (Some(userId), _) =>
              libraryInviteRepo.getLastSentByLibraryIdAndInviterIdAndUserId(libId, inviterId, userId, Set(LibraryInviteStates.ACTIVE))
            case (_, Some(email)) =>
              libraryInviteRepo.getLastSentByLibraryIdAndInviterIdAndEmail(libId, inviterId, email, Set(LibraryInviteStates.ACTIVE))
            case _ => None
          }
          (inviter, lib, libOwner, lastInviteOpt)
        }
        val invitesToPersist = inviteGroup.filter { invite =>
          lastInviteOpt.map { lastInvite =>
            lastInvite.access != invite.access || lastInvite.createdAt.plusMinutes(5).isBefore(invite.createdAt)
          }.getOrElse(true)
        }

        val persistedInvites = db.readWrite { implicit s =>
          invitesToPersist.map { inv =>
            libraryInviteRepo.save(inv)
          }
        }
        (persistedInvites, inviter, lib, libOwner)
    }.toSeq

    val emailFutures = groupedInvitesWithExtras.flatMap((notifyInvitees _).tupled)

    val emailsF = Future.sequence(emailFutures)
    emailsF map (_.filter(_.isDefined).map(_.get))
  }

  private def notifyInvitees(groupedInvites: Seq[LibraryInvite], inviter: User, lib: Library, libOwner: BasicUser) = {
    val userImage = s3ImageStore.avatarUrlByUser(inviter)
    val libLink = s"""https://www.kifi.com${Library.formatLibraryPath(libOwner.username, lib.slug)}"""
    val libImageOpt = libraryImageCommander.getBestImageForLibrary(lib.id.get, ProcessedImageSize.Medium.idealSize)

    val userInviteesMap = groupedInvites.collect {
      case invite if invite.userId.isDefined =>
        invite.userId.get -> invite
    }.toMap

    // send notifications to kifi users only
    if (userInviteesMap.nonEmpty) {
      notifyInviteeAboutInvitationToJoinLibrary(inviter, lib, libOwner, userImage, libLink, libImageOpt, userInviteesMap)
      if (inviter.id.get != lib.ownerId) {
        notifyLibOwnerAboutInvitationToTheirLibrary(inviter, lib, libOwner, userImage, libImageOpt, userInviteesMap)
      }
    }
    // send emails to both users & non-users
    groupedInvites.map { invite =>
      Try(invite.userId match {
        case Some(id) =>
          libraryInvitesAbuseMonitor.inspect(inviter.id.get, Some(id), None, lib.id.get, 1)
        case None =>
          libraryInvitesAbuseMonitor.inspect(inviter.id.get, None, invite.emailAddress, lib.id.get, 1)
      }).map { _ =>
        libraryInviteSender.get.sendInvite(invite)
      }.getOrElse(Future.successful(None))
    }
  }

  def notifyInviteeAboutInvitationToJoinLibrary(inviter: User, lib: Library, libOwner: BasicUser, userImage: String, libLink: String, libImageOpt: Option[LibraryImage], inviteeMap: Map[Id[User], LibraryInvite]): Unit = {
    val (collabInvitees, followInvitees) = inviteeMap.partition { case (userId, invite) => invite.isCollaborator }
    val collabInviteeSet = collabInvitees.keySet
    val followInviteeSet = followInvitees.keySet

    val collabInvitesF = elizaClient.sendGlobalNotification( //push sent
      userIds = collabInviteeSet,
      title = s"${inviter.firstName} ${inviter.lastName} invited you to collaborate on a Library!",
      body = s"Help ${libOwner.firstName} by sharing your knowledge in the library ${lib.name}.",
      linkText = "Let's do it!",
      linkUrl = libLink,
      imageUrl = userImage,
      sticky = false,
      category = NotificationCategory.User.LIBRARY_INVITATION,
      extra = Some(Json.obj(
        "inviter" -> inviter,
        "library" -> Json.toJson(LibraryNotificationInfo.fromLibraryAndOwner(lib, libImageOpt, libOwner)),
        "access" -> LibraryAccess.READ_WRITE
      ))
    )

    val followInvitesF = elizaClient.sendGlobalNotification( //push sent
      userIds = followInviteeSet,
      title = s"${inviter.firstName} ${inviter.lastName} invited you to follow a Library!",
      body = s"Browse keeps in ${lib.name} to find some interesting gems kept by ${libOwner.firstName}.",
      linkText = "Let's take a look!",
      linkUrl = libLink,
      imageUrl = userImage,
      sticky = false,
      category = NotificationCategory.User.LIBRARY_INVITATION,
      extra = Some(Json.obj(
        "inviter" -> inviter,
        "library" -> Json.toJson(LibraryNotificationInfo.fromLibraryAndOwner(lib, libImageOpt, libOwner)),
        "access" -> LibraryAccess.READ_ONLY
      ))
    )

    for {
      collabInvites <- collabInvitesF
      followInvites <- followInvitesF
    } yield {
      collabInviteeSet.foreach { userId =>
        val canSendPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(userId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
        if (canSendPush) {
          elizaClient.sendLibraryPushNotification(
            userId,
            s"""${inviter.firstName} ${inviter.lastName} invited you to contribute to: ${lib.name}""",
            lib.id.get,
            libLink,
            PushNotificationExperiment.Experiment1,
            LibraryPushNotificationCategory.LibraryInvitation)
        }
      }

      followInviteeSet.foreach { userId =>
        val canSendPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(userId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
        if (canSendPush) {
          elizaClient.sendLibraryPushNotification(
            userId,
            s"""${inviter.firstName} ${inviter.lastName} invited you to follow: ${lib.name}""",
            lib.id.get,
            libLink,
            PushNotificationExperiment.Experiment1,
            LibraryPushNotificationCategory.LibraryInvitation)
        }
      }
    }
  }

  def notifyLibOwnerAboutInvitationToTheirLibrary(inviter: User, lib: Library, libOwner: BasicUser, userImage: String, libImageOpt: Option[LibraryImage], inviteeMap: Map[Id[User], LibraryInvite]): Unit = {
    val (collabInvitees, followInvitees) = inviteeMap.partition { case (userId, invite) => invite.isCollaborator }
    val collabInviteeSet = collabInvitees.keySet
    val followInviteeSet = followInvitees.keySet
    val collabFriendStr = if (collabInviteeSet.size > 1) "friends" else "a friend"
    val followFriendStr = if (followInviteeSet.size > 1) "friends" else "a friend"

    val collabInvitesF = if (collabInviteeSet.nonEmpty) {
      elizaClient.sendGlobalNotification( //push sent
        userIds = Set(lib.ownerId),
        title = s"${inviter.firstName} invited someone to contribute to your Library!",
        body = s"${inviter.fullName} invited $collabFriendStr to contribute to your library, ${lib.name}.",
        linkText = s"See ${inviter.firstName}’s profile",
        linkUrl = s"https://www.kifi.com/${inviter.username.value}",
        imageUrl = userImage,
        sticky = false,
        category = NotificationCategory.User.LIBRARY_FOLLOWED,
        extra = Some(Json.obj(
          "inviter" -> inviter,
          "library" -> Json.toJson(LibraryNotificationInfo.fromLibraryAndOwner(lib, libImageOpt, libOwner))
        ))
      )
    } else {
      Future.successful((): Unit)
    }

    val followInvitesF = if (followInviteeSet.nonEmpty) {
      elizaClient.sendGlobalNotification( //push sent
        userIds = Set(lib.ownerId),
        title = s"${inviter.firstName} invited someone to follow your Library!",
        body = s"${inviter.fullName} invited $followFriendStr to follow your library, ${lib.name}.",
        linkText = s"See ${inviter.firstName}’s profile",
        linkUrl = s"https://www.kifi.com/${inviter.username.value}",
        imageUrl = userImage,
        sticky = false,
        category = NotificationCategory.User.LIBRARY_FOLLOWED,
        extra = Some(Json.obj(
          "inviter" -> inviter,
          "library" -> Json.toJson(LibraryNotificationInfo.fromLibraryAndOwner(lib, libImageOpt, libOwner))
        ))
      )
    } else {
      Future.successful((): Unit)
    }

    for {
      collabInvites <- collabInvitesF
      followInvites <- followInvitesF
    } yield {
      val canSendPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(lib.ownerId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
      if (canSendPush) {
        if (collabInviteeSet.nonEmpty) {
          elizaClient.sendUserPushNotification(
            userId = lib.ownerId,
            message = s"${inviter.firstName} invited $collabFriendStr to contribute to your library ${lib.name}!",
            recipient = inviter,
            pushNotificationExperiment = PushNotificationExperiment.Experiment1,
            category = UserPushNotificationCategory.NewLibraryFollower)
        }
        if (followInviteeSet.nonEmpty) {
          elizaClient.sendUserPushNotification(
            userId = lib.ownerId,
            message = s"${inviter.firstName} invited $followFriendStr to follow your library ${lib.name}!",
            recipient = inviter,
            pushNotificationExperiment = PushNotificationExperiment.Experiment1,
            category = UserPushNotificationCategory.NewLibraryFollower)
        }
      }
    }
  }

  def revokeInvitationToLibrary(libraryId: Id[Library], inviterId: Id[User], invitee: Either[ExternalId[User], EmailAddress]): Either[(String, String), String] = {
    val libraryInvite = invitee match {
      case Left(externalId) => db.readOnlyMaster { implicit s =>
        userRepo.getOpt(externalId) match {
          case Some(userId) => Right(libraryInviteRepo.getLastSentByLibraryIdAndInviterIdAndUserId(libraryId, inviterId, userId.id.get, Set(LibraryInviteStates.ACTIVE)))
          case None => Left(s"external_id_does_not_exist")
        }
      }
      case Right(email) => db.readOnlyMaster { implicit s =>
        Right(libraryInviteRepo.getLastSentByLibraryIdAndInviterIdAndEmail(libraryId, inviterId, email, Set(LibraryInviteStates.ACTIVE)))
      }
    }
    libraryInvite match {
      case Right(Some(toDelete)) => db.readWrite(attempts = 3) { implicit s =>
        libraryInviteRepo.save(toDelete.copy(state = LibraryInviteStates.INACTIVE)) match {
          case invite if invite.state == LibraryInviteStates.INACTIVE => Right("library_delete_succeeded")
          case _ => Left("error" -> "library_invite_delete_failed")
        }
      }
      case Right(None) => Left("error" -> "library_invite_not_found")
      case Left(error) => Left("error" -> error)
    }
  }

  def getViewerInviteInfo(userIdOpt: Option[Id[User]], libraryId: Id[Library]): Option[LibraryInviteInfo] = {
    userIdOpt.flatMap { userId =>
      db.readOnlyMaster { implicit s =>
        val inviteOpt = libraryInviteRepo.getLastSentByLibraryIdAndUserId(libraryId, userId, Set(LibraryInviteStates.ACTIVE))
        val basicUserOpt = inviteOpt map { inv => basicUserRepo.load(inv.inviterId) }
        (inviteOpt, basicUserOpt)
      } match {
        case (Some(invite), Some(inviter)) => Some(LibraryInviteInfo.createInfo(invite, inviter))
        case (_, _) => None
      }
    }
  }
}
