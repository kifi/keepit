package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time._
import com.keepit.eliza.{ ElizaServiceClient, PushNotificationExperiment, UserPushNotificationCategory }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.notify.NotificationInfoModel
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.{ OwnedLibraryNewFollower, OwnedLibraryNewCollaborator }
import com.keepit.search.SearchServiceClient
import com.keepit.social.BasicUser
import com.keepit.typeahead.KifiUserTypeahead
import org.joda.time.DateTime
import play.api.http.Status._
import com.keepit.common.core._
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

@ImplementedBy(classOf[LibraryMembershipCommanderImpl])
trait LibraryMembershipCommander {
  def updateMembership(requestorId: Id[User], request: ModifyLibraryMembershipRequest): Either[LibraryFail, LibraryMembership]
  def joinLibrary(userId: Id[User], libraryId: Id[Library], authToken: Option[String] = None, subscribed: Option[Boolean] = None)(implicit eventContext: HeimdalContext): Either[LibraryFail, (Library, LibraryMembership)]
  def leaveLibrary(libraryId: Id[Library], userId: Id[User])(implicit eventContext: HeimdalContext): Either[LibraryFail, Unit]
  def updateLibraryMembershipAccess(requestUserId: Id[User], libraryId: Id[Library], targetUserId: Id[User], newAccess: Option[LibraryAccess]): Either[LibraryFail, LibraryMembership]
  def suggestMembers(userId: Id[User], libraryId: Id[Library], query: Option[String], limit: Option[Int]): Future[Seq[MaybeLibraryMember]]
  def followDefaultLibraries(userId: Id[User]): Future[Map[Id[Library], LibraryMembership]]
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
    libraryAccessCommander: LibraryAccessCommander,
    organizationMembershipCommander: OrganizationMembershipCommander,
    typeaheadCommander: TypeaheadCommander,
    kifiUserTypeahead: KifiUserTypeahead,
    libraryAnalytics: LibraryAnalytics,
    elizaClient: ElizaServiceClient,
    searchClient: SearchServiceClient,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration,
    libraryImageCommander: LibraryImageCommander, // Only used by notifyOwnerOfNewFollowerOrCollaborator
    s3ImageStore: S3ImageStore, // Only used by notifyOwnerOfNewFollowerOrCollaborator
    kifiInstallationCommander: KifiInstallationCommander // Only used by notifyOwnerOfNewFollowerOrCollaborator
    ) extends LibraryMembershipCommander with Logging {

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
        libraryAccessCommander.getValidLibInvitesFromAuthToken(libraryId, authToken)
      } else Seq.empty
      val libInvites = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId, userId)
      val allInvites = tokenInvites ++ libInvites
      val existingActiveMembership = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
      (lib, allInvites, existingActiveMembership)
    }

    val isSystemGeneratedLibrary = lib.kind == LibraryKind.SYSTEM_MAIN || lib.kind == LibraryKind.SYSTEM_SECRET
    val userCanJoinLibraryWithoutInvite = libraryAccessCommander.canViewLibrary(Some(userId), lib, authToken) // uses a db session

    if (isSystemGeneratedLibrary) {
      Left(LibraryFail(FORBIDDEN, "cant_join_system_generated_library"))
    } else if (!userCanJoinLibraryWithoutInvite && inviteList.isEmpty && existingActiveMembership.isEmpty) {
      // private library & no library invites with matching authtoken
      Left(LibraryFail(FORBIDDEN, "cant_join_library_without_an_invite"))
    } else { // User can at least view the library, so can join as read_only. Determine if they could do better.
      val maxAccess: LibraryAccess = {
        val orgMemberAccess: Option[LibraryAccess] = if (lib.isSecret) None else lib.organizationId.flatMap { orgId =>
          lib.organizationMemberAccess.orElse(Some(LibraryAccess.READ_WRITE)).filter { _ => organizationMembershipCommander.getMembership(orgId, userId).isDefined }
        }
        (inviteList.map(_.access).toSet ++ orgMemberAccess).maxOpt.getOrElse(LibraryAccess.READ_ONLY)
      }
      val (updatedLib, updatedMem) = db.readWrite(attempts = 3) { implicit s =>
        val updatedMem = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId, None) match {
          case None =>
            val subscribedToUpdates = subscribed.getOrElse(maxAccess == LibraryAccess.READ_WRITE)
            log.info(s"[joinLibrary] New membership for $userId.  New access: $maxAccess. $inviteList")
            val mem = libraryMembershipRepo.save(LibraryMembership(libraryId = libraryId, userId = userId, access = maxAccess, lastJoinedAt = Some(clock.now), subscribedToUpdates = subscribedToUpdates))
            notifyOwnerOfNewFollowerOrCollaborator(userId, lib, maxAccess) // todo, bad, this is in a db transaction and side effects
            mem
          case Some(mem) =>
            val maxWithExisting = if (mem.state == LibraryMembershipStates.ACTIVE) Seq(maxAccess, mem.access).max else maxAccess
            val subscribedToUpdates = subscribed.getOrElse(maxWithExisting == LibraryAccess.READ_WRITE || mem.subscribedToUpdates)
            log.info(s"[joinLibrary] Modifying membership for ${mem.userId} / $userId. Old access: ${mem.access} (${mem.state}), new: $maxWithExisting. $maxAccess, $inviteList")
            libraryMembershipRepo.save(mem.copy(access = maxWithExisting, state = LibraryMembershipStates.ACTIVE, lastJoinedAt = Some(clock.now), subscribedToUpdates = subscribedToUpdates))
        }

        inviteList.foreach { inv =>
          // Only update invitations to a specific user. If it's to a specific recipient. Otherwise, leave it open for others.
          if (inv.userId.isDefined || inv.emailAddress.isDefined) {
            libraryInviteRepo.save(inv.copy(state = LibraryInviteStates.ACCEPTED))
          }
        }

        val invitesToAlert = inviteList.filterNot(_.inviterId == lib.ownerId)
        if (invitesToAlert.nonEmpty) {
          val invitee = userRepo.get(userId)
          val owner = basicUserRepo.load(lib.ownerId)
          libraryInviteCommander.notifyInviterOnLibraryInvitationAcceptance(invitesToAlert, invitee, lib, owner) // todo, bad, this is in a db transaction and side effects
        }

        val updatedLib = libraryRepo.save(lib.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libraryId)))
        (updatedLib, updatedMem)
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
    if (LibraryAccess.collaborativePermissions.contains(membership.access)) {
      refreshLibraryCollaboratorsTypeahead(libraryId)
    }
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
          if (LibraryAccess.collaborativePermissions.contains(mem.access)) {
            refreshLibraryCollaboratorsTypeahead(libraryId)
          }
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
                SafeFuture { refreshLibraryCollaboratorsTypeahead(library.id.get) }
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

  private def refreshLibraryCollaboratorsTypeahead(libraryId: Id[Library]): Future[Unit] = {
    val collaboratorIds = db.readOnlyMaster { implicit session =>
      libraryMembershipRepo.getCollaboratorsByLibrary(Set(libraryId)).get(libraryId).toSet.flatten
    }
    kifiUserTypeahead.refreshByIds(collaboratorIds.toSeq)
  }

  private def notifyOwnerOfNewFollowerOrCollaborator(newFollowerId: Id[User], lib: Library, access: LibraryAccess): Unit = SafeFuture {
    val (follower, owner, lotsOfFollowers) = db.readOnlyReplica { implicit session =>
      val follower = userRepo.get(newFollowerId)
      val owner = basicUserRepo.load(lib.ownerId)
      val lotsOfFollowers = libraryMembershipRepo.countMembersForLibrarySince(lib.id.get, DateTime.now().minusDays(1)) > 2
      (follower, owner, lotsOfFollowers)
    }
    val (title, category, message) = if (access == LibraryAccess.READ_WRITE) {
      // This should be changed to library_collaborated but right now iOS skips categories it doesn't know.
      ("New Library Collaborator", NotificationCategory.User.LIBRARY_FOLLOWED, s"${follower.firstName} ${follower.lastName} is now collaborating on your Library ${lib.name}")
    } else {
      ("New Library Follower", NotificationCategory.User.LIBRARY_FOLLOWED, s"${follower.firstName} ${follower.lastName} is now following your Library ${lib.name}")
    }
    val libImageOpt = libraryImageCommander.getBestImageForLibrary(lib.id.get, ProcessedImageSize.Medium.idealSize)
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
    if (access == LibraryAccess.READ_WRITE) {
      elizaClient.sendNotificationEvent(OwnedLibraryNewCollaborator(
        Recipient(lib.ownerId),
        currentDateTime,
        newFollowerId,
        lib.id.get
      ))
    } else {
      elizaClient.sendNotificationEvent(OwnedLibraryNewFollower(
        Recipient(lib.ownerId),
        currentDateTime,
        newFollowerId,
        lib.id.get
      ))
    }
  }

  def suggestMembers(userId: Id[User], libraryId: Id[Library], query: Option[String], limit: Option[Int]): Future[Seq[MaybeLibraryMember]] = {
    val futureFriendsAndContacts = query.map(_.trim).filter(_.nonEmpty) match {
      case Some(validQuery) => typeaheadCommander.searchFriendsAndContacts(userId, validQuery, limit)
      case None => Future.successful(typeaheadCommander.suggestFriendsAndContacts(userId, limit))
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

  def followDefaultLibraries(userId: Id[User]): Future[Map[Id[Library], LibraryMembership]] = SafeFuture {
    println(s"signing $userId up for ${LibraryMembershipCommander.defaultLibraries}")
    implicit val context = HeimdalContext.empty
    LibraryMembershipCommander.defaultLibraries.map { libId =>
      val membership = joinLibrary(userId, libId) match {
        case Left(fail) =>
          println("failed!: " + fail)
          throw fail
        case Right((_, mem)) => mem
      }
      libId -> membership
    }.toMap
  }
}
