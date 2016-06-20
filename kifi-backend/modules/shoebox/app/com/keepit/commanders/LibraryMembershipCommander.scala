package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Provider, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.BasicContact
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.eliza.{ ElizaServiceClient, PushNotificationExperiment, UserPushNotificationCategory }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.{ OwnedLibraryNewCollaborator, OwnedLibraryNewFollower }
import com.keepit.search.SearchServiceClient
import com.keepit.typeahead.{ KifiUserTypeahead, LibraryTypeahead }
import org.joda.time.DateTime
import play.api.Mode.Mode
import play.api.http.Status._

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[LibraryMembershipCommanderImpl])
trait LibraryMembershipCommander {
  def updateMembership(requestorId: Id[User], request: ModifyLibraryMembershipRequest): Either[LibraryFail, LibraryMembership]
  def joinLibrary(userId: Id[User], libraryId: Id[Library], authToken: Option[String] = None, subscribed: Option[Boolean] = None)(implicit eventContext: HeimdalContext): Either[LibraryFail, (Library, LibraryMembership)]
  def leaveLibrary(libraryId: Id[Library], userId: Id[User])(implicit eventContext: HeimdalContext): Either[LibraryFail, Unit]
  def updateLibraryMembershipAccess(requestUserId: Id[User], libraryId: Id[Library], targetUserId: Id[User], newAccess: Option[LibraryAccess]): Either[LibraryFail, LibraryMembership]
  def suggestMembers(userId: Id[User], libraryId: Id[Library], query: Option[String], limit: Option[Int]): Future[Seq[MaybeLibraryMember]]
  def followDefaultLibraries(userId: Id[User]): Future[Map[Id[Library], LibraryMembership]]
  def createMembershipInfo(mem: LibraryMembership)(implicit session: RSession): LibraryMembershipInfo
  def getViewerMembershipInfo(userIdOpt: Option[Id[User]], libraryId: Id[Library]): Option[LibraryMembershipInfo]
  def getLibrariesWithWriteAccess(userId: Id[User]): Set[Id[Library]]
}

object LibraryMembershipCommander {
  val kifiTutorial = Id[Library](622462)
  val defaultLibraries = Set(kifiTutorial)
}

case class ModifyLibraryMembershipRequest(userId: Id[User], libraryId: Id[Library],
  subscription: Option[Boolean] = None,
  priority: Option[Long] = None,
  listed: Option[Boolean] = None,
  access: Option[LibraryAccess] = None)

@Singleton
class LibraryMembershipCommanderImpl @Inject() (
    clock: Clock,
    db: Database,
    userRepo: UserRepo,
    basicUserRepo: BasicUserRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryRepo: LibraryRepo,
    libraryInviteRepo: LibraryInviteRepo,
    libraryInviteCommander: LibraryInviteCommander,
    libraryAccessCommanderProvider: Provider[LibraryAccessCommander],
    permissionCommander: PermissionCommander,
    organizationMembershipRepo: OrganizationMembershipRepo,
    typeaheadCommander: TypeaheadCommander,
    userInteractionCommander: UserInteractionCommander,
    kifiUserTypeahead: KifiUserTypeahead,
    libraryTypeahead: Provider[LibraryTypeahead],
    relevantSuggestedLibrariesCache: RelevantSuggestedLibrariesCache,
    libraryAnalytics: LibraryAnalytics,
    elizaClient: ElizaServiceClient,
    searchClient: SearchServiceClient,
    libraryResultCache: LibraryResultCache,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration,
    kifiInstallationCommander: KifiInstallationCommander, // Only used by notifyOwnerOfNewFollowerOrCollaborator
    mode: Mode) extends LibraryMembershipCommander with Logging {

  def updateMembership(requestorId: Id[User], request: ModifyLibraryMembershipRequest): Either[LibraryFail, LibraryMembership] = {
    val (requestorMembership, targetMembershipOpt) = db.readOnlyMaster { implicit s =>
      val requestorMembership = libraryMembershipRepo.getWithLibraryIdAndUserId(request.libraryId, requestorId)
      val targetMembershipOpt = libraryMembershipRepo.getWithLibraryIdAndUserId(request.libraryId, request.userId)
      (requestorMembership, targetMembershipOpt)
    }
    (for {
      requester <- requestorMembership
      targetMembership <- targetMembershipOpt
    } yield {
      def modifySelfCheck[T](valueOpt: Option[T], fallback: => T): Either[LibraryFail, T] = {
        valueOpt match {
          case Some(value) =>
            if (requestorId == targetMembership.userId) {
              Right(value)
            } else {
              Left(LibraryFail(BAD_REQUEST, "permission_denied"))
            }
          case None => Right(fallback)
        }
      }

      def canChangeAccess(accessOpt: Option[LibraryAccess]) = {
        accessOpt match {
          case Some(access) =>
            access match {
              case LibraryAccess.READ_WRITE | LibraryAccess.READ_ONLY if (requester.isOwner && targetMembership.access != LibraryAccess.OWNER) =>
                Right(access)
              case _ => Left(LibraryFail(BAD_REQUEST, "permission_denied"))
            }
          case None => Right(targetMembership.access)
        }
      }

      for {
        starred <- modifySelfCheck(request.priority, targetMembership.priority).right
        subscribed <- modifySelfCheck(request.subscription, targetMembership.subscribedToUpdates).right
        isListed <- modifySelfCheck(request.listed, targetMembership.listed).right
        access <- canChangeAccess(request.access).right
      } yield {
        val modifiedLib = targetMembership.copy(priority = starred, subscribedToUpdates = subscribed, listed = isListed, access = access)
        if (targetMembership != modifiedLib) {
          db.readWrite { implicit session =>
            libraryMembershipRepo.save(modifiedLib)
          }
        } else {
          targetMembership
        }
      }
    }).getOrElse(Left(LibraryFail(BAD_REQUEST, "permission_denied")))
  }

  def joinLibrary(userId: Id[User], libraryId: Id[Library], authToken: Option[String] = None, subscribed: Option[Boolean] = None)(implicit eventContext: HeimdalContext): Either[LibraryFail, (Library, LibraryMembership)] = {
    val (lib, inviteList, existingActiveMembership) = db.readOnlyMaster { implicit s =>
      val lib = libraryRepo.get(libraryId)
      val tokenInvites = if (authToken.isDefined) {
        libraryInviteRepo.getByLibraryIdAndAuthToken(libraryId, authToken.get)
      } else Seq.empty
      val libInvites = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId, userId)
      val allInvites = tokenInvites ++ libInvites
      val existingActiveMembership = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
      (lib, allInvites, existingActiveMembership)
    }

    val isSystemGeneratedLibrary = lib.kind == LibraryKind.SYSTEM_MAIN || lib.kind == LibraryKind.SYSTEM_SECRET
    val userCanJoinLibraryWithoutInvite = libraryAccessCommanderProvider.get.canViewLibrary(Some(userId), lib, authToken) // uses a db session

    if (isSystemGeneratedLibrary) {
      Left(LibraryFail(FORBIDDEN, "cant_join_system_generated_library"))
    } else if (!userCanJoinLibraryWithoutInvite && inviteList.isEmpty && existingActiveMembership.isEmpty) {
      // private library & no library invites with matching authtoken
      Left(LibraryFail(FORBIDDEN, "cant_join_library_without_an_invite"))
    } else { // User can at least view the library, so can join as read_only. Determine if they could do better.
      val maxAccess: LibraryAccess = {
        val orgMemberAccess: Option[LibraryAccess] = if (lib.isSecret) None else lib.organizationId.flatMap { orgId =>
          lib.organizationMemberAccess.orElse(Some(LibraryAccess.READ_WRITE)).filter { _ => db.readOnlyMaster { implicit s => organizationMembershipRepo.getByOrgIdAndUserId(orgId, userId).isDefined } }
        }
        (inviteList.map(_.access).toSet ++ orgMemberAccess).maxOpt.getOrElse(LibraryAccess.READ_ONLY)
      }
      val (updatedLib, updatedMem, invitesToAlert) = db.readWrite(attempts = 3) { implicit s =>
        val updatedMem = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId, None) match {
          case Some(membership) if membership.isActive =>
            val maxWithExisting = Seq(maxAccess, membership.access).max
            val subscribedToUpdates = subscribed.getOrElse(maxWithExisting == LibraryAccess.READ_WRITE || membership.subscribedToUpdates)
            val updated = membership.copy(access = maxWithExisting, subscribedToUpdates = subscribedToUpdates)
            if (updated == membership) membership else {
              log.info(s"[joinLibrary] Modifying membership for ${membership.userId} / $userId. Old access: ${membership.access}), new: $maxWithExisting. $maxAccess, $inviteList")
              libraryMembershipRepo.save(updated)
            }
          case inactiveMembershipOpt =>
            val subscribedToUpdates = subscribed.getOrElse(maxAccess == LibraryAccess.READ_WRITE)
            log.info(s"[joinLibrary] New membership for $userId. New access: $maxAccess. $inviteList")
            val newMembership = LibraryMembership(libraryId = libraryId, userId = userId, access = maxAccess, subscribedToUpdates = subscribedToUpdates)
            libraryMembershipRepo.save(newMembership.copy(id = inactiveMembershipOpt.flatMap(_.id)))
        }

        inviteList.foreach { inv =>
          // Only update invitations to a specific user. If it's to a specific recipient. Otherwise, leave it open for others.
          if (inv.userId.isDefined || inv.emailAddress.isDefined) {
            libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.ACCEPTED))
          }
        }

        val invitesToAlert = inviteList.filterNot(_.inviterId == lib.ownerId)

        val updatedLib = libraryRepo.save(lib.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libraryId)))
        (updatedLib, updatedMem, invitesToAlert)
      }

      if (lib.kind != LibraryKind.SYSTEM_ORG_GENERAL) {
        notifyOwnerOfNewFollowerOrCollaborator(userId, lib, maxAccess)
        if (invitesToAlert.nonEmpty) libraryInviteCommander.notifyInviterOnLibraryInvitationAcceptance(invitesToAlert, userId, lib)
      }

      updateLibraryJoin(userId, lib, updatedMem, eventContext)
      Right((updatedLib, updatedMem))
    }
  }

  private def updateLibraryJoin(userId: Id[User], library: Library, membership: LibraryMembership, eventContext: HeimdalContext): Future[Unit] = SafeFuture {
    val libraryId = library.id.get
    libraryAnalytics.acceptLibraryInvite(userId, library, eventContext)
    libraryAnalytics.followLibrary(userId, library, eventContext)
    searchClient.updateLibraryIndex()
    refreshTypeaheads(userId, libraryId)
  }

  def leaveLibrary(libraryId: Id[Library], userId: Id[User])(implicit eventContext: HeimdalContext): Either[LibraryFail, Unit] = {
    db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId, None)
    } match {
      case None => Right((): Unit)
      case Some(mem) if mem.access == LibraryAccess.OWNER => Left(LibraryFail(BAD_REQUEST, "cannot_leave_own_library"))
      case Some(mem) =>
        val lib = db.readWrite { implicit s =>
          libraryMembershipRepo.save(mem.copy(state = LibraryMembershipStates.INACTIVE))
          val lib = libraryRepo.get(libraryId)
          libraryRepo.save(lib.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libraryId)))
          lib
        }
        SafeFuture {
          libraryAnalytics.unfollowLibrary(userId, lib, eventContext)
          searchClient.updateLibraryIndex()
          refreshTypeaheads(userId, libraryId)
        }
        Right((): Unit)
    }
  }

  ///////////////////
  // Collaborators!
  ///////////////////

  def updateLibraryMembershipAccess(requestUserId: Id[User], libraryId: Id[Library], targetUserId: Id[User], newAccess: Option[LibraryAccess]): Either[LibraryFail, LibraryMembership] = {
    if (newAccess.isDefined && newAccess.get == LibraryAccess.OWNER) {
      Left(LibraryFail(BAD_REQUEST, "cannot_change_access_to_owner"))
    } else {
      db.readOnlyMaster { implicit s =>
        val membershipMap = libraryMembershipRepo.getWithLibraryIdAndUserIds(libraryId, Set(requestUserId, targetUserId))
        val library = libraryRepo.get(libraryId)
        (membershipMap.get(requestUserId), membershipMap.get(targetUserId), library)
      } match {
        case (None, _, _) =>
          Left(LibraryFail(NOT_FOUND, "request_membership_not_found"))
        case (_, None, _) =>
          Left(LibraryFail(NOT_FOUND, "target_membership_not_found"))
        case (Some(mem), Some(targetMem), _) if targetMem.access == LibraryAccess.OWNER =>
          Left(LibraryFail(BAD_REQUEST, "cannot_change_owner_access"))

        case (Some(requesterMem), Some(targetMem), library) =>

          if ((requesterMem.isOwner && !targetMem.isOwner) || // owners can edit anyone except themselves
            (requesterMem.isCollaborator && !targetMem.isOwner) || // a collaborator can edit anyone (but the owner). Collaborator cannot invite others to collaborate if the library does not allow collaborators to invite
            (requesterMem.isFollower && requesterMem.userId == targetMem.userId && !newAccess.exists(_.priority > targetMem.access.priority))) { // a follower can only edit herself
            db.readWrite { implicit s =>
              newAccess match {
                case None =>
                  Right(libraryMembershipRepo.save(targetMem.copy(state = LibraryMembershipStates.INACTIVE)))
                case Some(newAccess) if requesterMem.isCollaborator && newAccess == LibraryAccess.READ_WRITE && library.whoCanInvite == Some(LibraryInvitePermissions.OWNER) =>
                  log.warn(s"[updateLibraryMembership] invalid permission ${requesterMem} trying to change membership ${targetMem} to ${newAccess} when library has invite policy ${library.whoCanInvite}")
                  Left(LibraryFail(FORBIDDEN, "invalid_collaborator_permission"))
                case Some(newAccess) =>
                  val newSubscription = if (newAccess == LibraryAccess.READ_WRITE) true else targetMem.subscribedToUpdates // auto subscribe to updates if a collaborator
                  val inviter = userRepo.get(requestUserId)
                  val libOwner = basicUserRepo.load(library.ownerId)
                  val updatedTargetMembership = libraryMembershipRepo.save(targetMem.copy(access = newAccess, subscribedToUpdates = newSubscription, state = LibraryMembershipStates.ACTIVE))

                  if (inviter.id.get.id != targetUserId.id) {
                    libraryInviteCommander.notifyInviteeAboutInvitationToJoinLibrary(inviter, library, libOwner, Map(targetUserId -> updatedTargetMembership))
                  }

                  Right(updatedTargetMembership)
              }
            } tap {
              // Unless we're just kicking out a follower, the set of collaborators has changed.
              case Right(updatedMembership) if !(updatedMembership.isFollower && updatedMembership.state == LibraryMembershipStates.INACTIVE) => {
                refreshTypeaheads(targetUserId, library.id.get)
              }
              case _ => //
            }
          } else { // invalid permissions
            log.warn(s"[updateLibraryMembership] invalid permission ${requesterMem} trying to change membership ${targetMem} to ${newAccess}")
            Left(LibraryFail(FORBIDDEN, "invalid_permissions"))
          }
      }
    }
  }

  private def refreshTypeaheads(userId: Id[User], libraryId: Id[Library]): Future[Unit] = {
    libraryResultCache.direct.remove(LibraryResultKey(userId, libraryId))
    relevantSuggestedLibrariesCache.direct.remove(RelevantSuggestedLibrariesKey(userId))
    libraryTypeahead.get.refresh(userId).map { _ =>
      // Each of the existing collaborators can now message `userId`, so refresh their user typeaheads.
      val collaboratorIds = db.readOnlyMaster { implicit session =>
        libraryMembershipRepo.getCollaboratorsByLibrary(Set(libraryId)).get(libraryId).toSet.flatten
      }
      kifiUserTypeahead.refreshByIds(collaboratorIds.toSeq)
    }
  }

  private def notifyOwnerOfNewFollowerOrCollaborator(newFollowerId: Id[User], lib: Library, access: LibraryAccess): Unit = SafeFuture {
    val (follower, lotsOfFollowers) = db.readOnlyReplica { implicit session =>
      val follower = userRepo.get(newFollowerId)
      val lotsOfFollowers = libraryMembershipRepo.countMembersForLibrarySince(lib.id.get, DateTime.now().minusDays(4)) > 2
      (follower, lotsOfFollowers)
    }
    val (category, message) = if (access == LibraryAccess.READ_WRITE) {
      (NotificationCategory.User.LIBRARY_FOLLOWED, s"${follower.firstName} ${follower.lastName} is now collaborating on your Library ${lib.name}")
    } else {
      (NotificationCategory.User.LIBRARY_FOLLOWED, s"${follower.firstName} ${follower.lastName} is now following your Library ${lib.name}")
    }
    if (!lotsOfFollowers) {
      val canSendPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(lib.ownerId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
      if (canSendPush) {
        val pushCat = category match {
          case NotificationCategory.User.LIBRARY_COLLABORATED => UserPushNotificationCategory.NewLibraryCollaborator
          case _ => UserPushNotificationCategory.NewLibraryFollower
        }
        elizaClient.sendUserPushNotification(
          userId = lib.ownerId,
          message = message,
          recipient = follower,
          pushNotificationExperiment = PushNotificationExperiment.Experiment1,
          category = pushCat)
      }
    }

    if (lib.kind != LibraryKind.SYSTEM_ORG_GENERAL) {
      access match {
        case LibraryAccess.READ_WRITE =>
          elizaClient.sendNotificationEvent(OwnedLibraryNewCollaborator(
            Recipient.fromUser(lib.ownerId),
            currentDateTime,
            newFollowerId,
            lib.id.get
          ))
        case LibraryAccess.READ_ONLY =>
          elizaClient.sendNotificationEvent(OwnedLibraryNewFollower(
            Recipient.fromUser(lib.ownerId),
            currentDateTime,
            newFollowerId,
            lib.id.get
          ))
        case _ =>
      }
    }
  }

  def suggestMembers(userId: Id[User], libraryId: Id[Library], query: Option[String], limit: Option[Int]): Future[Seq[MaybeLibraryMember]] = {
    val futureFriendsAndContacts = query.map(_.trim).filter(_.nonEmpty) match {
      case Some(validQuery) => typeaheadCommander.searchForContacts(userId, validQuery, limit, includeSelf = false)
      case None =>
        val (userIds, emails) = userInteractionCommander.suggestFriendsAndContacts(userId, limit)
        val usersById = db.readOnlyMaster { implicit session => basicUserRepo.loadAll(userIds.toSet) }
        val users = userIds.map(id => id -> usersById(id))
        val contacts = emails.map { email => BasicContact(email = email) }
        Future.successful((users, contacts))
    }

    val activeInvites = db.readOnlyMaster { implicit session =>
      libraryInviteRepo.getByLibraryIdAndInviterId(libraryId, userId, Set(LibraryInviteStates.ACTIVE))
    }

    val invitedUsers = activeInvites.groupBy(_.userId).collect {
      case (Some(userId), invites) =>
        val access = invites.map(_.access).max
        val lastInvitedAt = invites.map(_.createdAt).max
        userId -> (access, lastInvitedAt)
    }

    val invitedEmailAddresses = activeInvites.groupBy(_.emailAddress).collect {
      case (Some(emailAddress), invites) =>
        val access = invites.map(_.access).max
        val lastInvitedAt = invites.map(_.createdAt).max
        emailAddress -> (access, lastInvitedAt)
    }

    futureFriendsAndContacts.map {
      case (users, contacts) =>
        val existingMembers = {
          val userIds = users.map(_._1).toSet
          val memberships = db.readOnlyMaster { implicit session => libraryMembershipRepo.getWithLibraryIdAndUserIds(libraryId, userIds) }
          memberships.mapValues(_.access)
        }
        val suggestedUsers = users.map {
          case (userId, basicUser) =>
            val (access, lastInvitedAt) = existingMembers.get(userId) match {
              case Some(access) => (Some(access), None)
              case None => invitedUsers.get(userId) match {
                case Some((access, lastInvitedAt)) => (Some(access), Some(lastInvitedAt))
                case None => (None, None)
              }
            }
            MaybeLibraryMember(Left(basicUser), access, lastInvitedAt)
        }

        val suggestedEmailAddresses = contacts.map { contact =>
          val (access, lastInvitedAt) = invitedEmailAddresses.get(contact.email) match {
            case Some((access, lastInvitedAt)) => (Some(access), Some(lastInvitedAt))
            case None => (None, None)
          }
          MaybeLibraryMember(Right(contact), access, lastInvitedAt)
        }
        suggestedUsers ++ suggestedEmailAddresses
    }
  }

  def followDefaultLibraries(userId: Id[User]): Future[Map[Id[Library], LibraryMembership]] = {
    if (mode != play.api.Mode.Prod) Future.successful(Map.empty)
    else SafeFuture {
      implicit val context = HeimdalContext.empty
      LibraryMembershipCommander.defaultLibraries.map { libId =>
        val membership = joinLibrary(userId, libId) match {
          case Left(fail) => throw fail
          case Right((_, mem)) => mem
        }
        libId -> membership
      }.toMap
    }
  }

  def createMembershipInfo(mem: LibraryMembership)(implicit session: RSession): LibraryMembershipInfo = {
    LibraryMembershipInfo(mem.access, mem.listed, mem.subscribedToUpdates, permissionCommander.getLibraryPermissions(mem.libraryId, Some(mem.userId)))
  }

  def getViewerMembershipInfo(userIdOpt: Option[Id[User]], libraryId: Id[Library]): Option[LibraryMembershipInfo] = {
    userIdOpt.flatMap { userId =>
      db.readOnlyReplica { implicit s =>
        val membershipOpt = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
        membershipOpt.map(createMembershipInfo)
      }
    }
  }

  def getLibrariesWithWriteAccess(userId: Id[User]): Set[Id[Library]] = {
    db.readOnlyMaster { implicit session => libraryMembershipRepo.getLibrariesWithWriteAccess(userId) }
  }
}
