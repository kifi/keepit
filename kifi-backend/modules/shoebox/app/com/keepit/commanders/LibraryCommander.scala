package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time._
import com.keepit.eliza.{ ElizaServiceClient, PushNotificationExperiment, UserPushNotificationCategory }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model.OrganizationPermission._
import com.keepit.model._
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.{ OwnedLibraryNewCollaborator, OwnedLibraryNewFollower }
import com.keepit.search.SearchServiceClient
import com.keepit.social.BasicUser
import com.keepit.typeahead.KifiUserTypeahead
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.http.Status._
import play.api.libs.json._

import scala.concurrent._

@json case class MarketingSuggestedLibrarySystemValue(
  id: Id[Library],
  caption: Option[String] = None)

object MarketingSuggestedLibrarySystemValue {
  // system value that persists the library IDs and additional library data for the marketing site
  def systemValueName = Name[SystemValue]("marketing_site_libraries")
}

@ImplementedBy(classOf[LibraryCommanderImpl])
trait LibraryCommander {
  def updateLastView(userId: Id[User], libraryId: Id[Library]): Unit
  def suggestMembers(userId: Id[User], libraryId: Id[Library], query: Option[String], limit: Option[Int]): Future[Seq[MaybeLibraryMember]]
  def createLibrary(libCreateReq: LibraryCreateRequest, ownerId: Id[User])(implicit context: HeimdalContext): Either[LibraryFail, Library]
  def canModifyLibrary(libraryId: Id[Library], userId: Id[User]): Boolean
  def modifyLibrary(libraryId: Id[Library], userId: Id[User], modifyReq: LibraryModifyRequest)(implicit context: HeimdalContext): Either[LibraryFail, LibraryModifyResponse]
  def unsafeModifyLibrary(library: Library, modifyReq: LibraryModifyRequest): LibraryModifyResponse
  def deleteLibrary(libraryId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Option[LibraryFail]
  def canMoveTo(userId: Id[User], libId: Id[Library], to: LibrarySpace): Boolean
  def createReadItLaterLibrary(userId: Id[User]): Library
  def joinLibrary(userId: Id[User], libraryId: Id[Library], authToken: Option[String] = None, subscribed: Option[Boolean] = None)(implicit eventContext: HeimdalContext): Either[LibraryFail, (Library, LibraryMembership)]
  def leaveLibrary(libraryId: Id[Library], userId: Id[User])(implicit eventContext: HeimdalContext): Either[LibraryFail, Unit]
  def copyKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag)(implicit context: HeimdalContext): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])]
  def moveKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag)(implicit context: HeimdalContext): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])]
  def copyKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Set[Keep], withSource: Option[KeepSource])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)])
  def moveKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Seq[Keep])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)])
  def moveAllKeepsFromLibrary(userId: Id[User], fromLibraryId: Id[Library], toLibraryId: Id[Library])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)])
  def trackLibraryView(viewerId: Option[Id[User]], library: Library)(implicit context: HeimdalContext): Unit
  def updateLastEmailSent(userId: Id[User], keeps: Seq[Keep]): Unit
  def updateSubscribedToLibrary(userId: Id[User], libraryId: Id[Library], subscribedToUpdatesNew: Boolean): Either[LibraryFail, LibraryMembership]
  def updateLibraryMembershipAccess(requestUserId: Id[User], libraryId: Id[Library], targetUserId: Id[User], newAccess: Option[LibraryAccess]): Either[LibraryFail, LibraryMembership]
  def unsafeTransferLibrary(libraryId: Id[Library], newOwner: Id[User]): Library
}

class LibraryCommanderImpl @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryAliasRepo: LibraryAliasRepo,
    libraryInviteRepo: LibraryInviteRepo,
    libraryInviteCommander: LibraryInviteCommander,
    librarySubscriptionCommander: LibrarySubscriptionCommander,
    orgMembershipCommander: OrganizationMembershipCommander,
    userRepo: UserRepo,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    keepCommander: KeepCommander,
    keepToCollectionRepo: KeepToCollectionRepo,
    ktlRepo: KeepToLibraryRepo,
    ktlCommander: KeepToLibraryCommander,
    libraryAccessCommander: LibraryAccessCommander,
    countByLibraryCache: CountByLibraryCache,
    typeaheadCommander: TypeaheadCommander,
    kifiUserTypeahead: KifiUserTypeahead,
    collectionRepo: CollectionRepo,
    s3ImageStore: S3ImageStore,
    airbrake: AirbrakeNotifier,
    searchClient: SearchServiceClient,
    elizaClient: ElizaServiceClient,
    libraryAnalytics: LibraryAnalytics,
    libraryImageCommander: LibraryImageCommander,
    kifiInstallationCommander: KifiInstallationCommander,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration,
    clock: Clock) extends LibraryCommander with Logging {

  def updateLastView(userId: Id[User], libraryId: Id[Library]): Unit = {
    Future {
      db.readWrite { implicit s =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId).map { mem =>
          libraryMembershipRepo.updateLastViewed(mem.id.get) // do not update seq num
        }
      }
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
        val access = invites.map(_.access).maxBy(_.priority)
        val lastInvitedAt = invites.map(_.createdAt).maxBy(_.getMillis)
        userId -> (access, lastInvitedAt)
    }

    val invitedEmailAddresses = activeInvites.groupBy(_.emailAddress).collect {
      case (Some(emailAddress), invites) =>
        val access = invites.map(_.access).maxBy(_.priority)
        val lastInvitedAt = invites.map(_.createdAt).maxBy(_.getMillis)
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

  def createLibrary(libCreateReq: LibraryCreateRequest, ownerId: Id[User])(implicit context: HeimdalContext): Either[LibraryFail, Library] = {
    val badMessage: Option[String] = {
      if (libCreateReq.name.isEmpty || !Library.isValidName(libCreateReq.name)) {
        log.info(s"[addLibrary] Invalid name ${libCreateReq.name} for $ownerId")
        Some("invalid_name")
      } else if (libCreateReq.slug.isEmpty || !LibrarySlug.isValidSlug(libCreateReq.slug)) {
        log.info(s"[addLibrary] Invalid slug ${libCreateReq.slug} for $ownerId")
        Some("invalid_slug")
      } else if (LibrarySlug.isReservedSlug(libCreateReq.slug)) {
        log.info(s"[addLibrary] Attempted reserved slug ${libCreateReq.slug} for $ownerId")
        Some("reserved_slug")
      } else {
        None
      }
    }
    badMessage match {
      case Some(x) => Left(LibraryFail(BAD_REQUEST, x))
      case _ => {
        val validSlug = LibrarySlug(libCreateReq.slug)
        val targetSpace = libCreateReq.space.getOrElse(LibrarySpace.fromUserId(ownerId))
        val orgIdOpt = targetSpace match {
          case OrganizationSpace(orgId) => Some(orgId)
          case _ => None
        }
        db.readOnlyReplica { implicit s =>
          val userHasPermissionToCreateInSpace = targetSpace match {
            case OrganizationSpace(orgId) =>
              orgMembershipCommander.getPermissionsHelper(orgId, Some(ownerId)).contains(OrganizationPermission.ADD_LIBRARIES)
            case UserSpace(userId) =>
              userId == ownerId // Right now this is guaranteed to be correct, could replace with true
          }
          val sameNameOpt = libraryRepo.getBySpaceAndName(targetSpace, libCreateReq.name)
          val sameSlugOpt = libraryRepo.getBySpaceAndSlug(targetSpace, validSlug)
          (userHasPermissionToCreateInSpace, sameNameOpt, sameSlugOpt)
        } match {
          case (false, _, _) =>
            Left(LibraryFail(FORBIDDEN, "cannot_add_library_to_space"))
          case (_, Some(sameName), _) =>
            Left(LibraryFail(BAD_REQUEST, "library_name_exists"))
          case (_, _, Some(sameSlug)) =>
            Left(LibraryFail(BAD_REQUEST, "library_slug_exists"))
          case (_, None, None) =>
            val newColor = libCreateReq.color.orElse(Some(LibraryColor.pickRandomLibraryColor()))
            val newListed = libCreateReq.listed.getOrElse(true)
            val newKind = libCreateReq.kind.getOrElse(LibraryKind.USER_CREATED)
            val newInviteToCollab = libCreateReq.whoCanInvite.orElse(Some(LibraryInvitePermissions.COLLABORATOR))
            val library = db.readWrite { implicit s =>
              libraryAliasRepo.reclaim(targetSpace, validSlug) // there's gonna be a real library there, dump the alias
              libraryRepo.getBySpaceAndSlug(ownerId, validSlug, excludeState = None) match {
                case None =>
                  val lib = libraryRepo.save(Library(ownerId = ownerId, name = libCreateReq.name, description = libCreateReq.description,
                    visibility = libCreateReq.visibility, slug = validSlug, color = newColor, kind = newKind,
                    memberCount = 1, keepCount = 0, whoCanInvite = newInviteToCollab, organizationId = orgIdOpt))
                  libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = ownerId, access = LibraryAccess.OWNER, listed = newListed, lastJoinedAt = Some(currentDateTime)))
                  libCreateReq.subscriptions match {
                    case Some(subKeys) => librarySubscriptionCommander.updateSubsByLibIdAndKey(lib.id.get, subKeys)
                    case None =>
                  }
                  lib
                case Some(lib) =>
                  val newLib = libraryRepo.save(lib.copy(state = LibraryStates.ACTIVE))
                  libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId = lib.id.get, userId = ownerId, None) match {
                    case None => libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = ownerId, access = LibraryAccess.OWNER))
                    case Some(mem) => libraryMembershipRepo.save(mem.copy(state = LibraryMembershipStates.ACTIVE, listed = newListed))
                  }
                  libCreateReq.subscriptions match {
                    case Some(subKeys) => librarySubscriptionCommander.updateSubsByLibIdAndKey(lib.id.get, subKeys)
                    case None =>
                  }
                  newLib
              }
            }
            SafeFuture {
              libraryAnalytics.createLibrary(ownerId, library, context)
              searchClient.updateLibraryIndex()
            }
            Right(library)
        }
      }
    }
  }

  def canModifyLibrary(libraryId: Id[Library], userId: Id[User]): Boolean = {
    db.readOnlyReplica { implicit s =>
      val lib = libraryRepo.get(libraryId)
      val libMembershipOpt = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
      def canDirectlyEditLibrary = libMembershipOpt.exists(_.canWrite)
      def canIndirectlyEditLibrary = libMembershipOpt.isDefined && lib.organizationId.exists { orgId =>
        orgMembershipCommander.getPermissionsHelper(orgId, Some(userId)).contains(OrganizationPermission.FORCE_EDIT_LIBRARIES)
      }
      canDirectlyEditLibrary || canIndirectlyEditLibrary
    }
  }

  def validateModifyRequest(library: Library, userId: Id[User], modifyReq: LibraryModifyRequest): Option[LibraryFail] = {
    def validateUserWritePermission: Option[LibraryFail] = {
      if (canModifyLibrary(library.id.get, userId)) None
      else Some(LibraryFail(FORBIDDEN, "permission_denied"))
    }

    def validateSpace(newSpaceOpt: Option[LibrarySpace]): Option[LibraryFail] = {
      newSpaceOpt.flatMap { newSpace =>
        if (!canMoveTo(userId = userId, libId = library.id.get, to = newSpace)) Some(LibraryFail(BAD_REQUEST, "invalid_space"))
        else None
      }
    }

    def validateName(newNameOpt: Option[String], newSpace: LibrarySpace): Option[LibraryFail] = {
      newNameOpt.flatMap { name =>
        if (!Library.isValidName(name)) {
          Some(LibraryFail(BAD_REQUEST, "invalid_name"))
        } else {
          db.readOnlyMaster { implicit s =>
            libraryRepo.getBySpaceAndName(newSpace, name)
          } match {
            case Some(other) if other.id.get != library.id.get => Some(LibraryFail(BAD_REQUEST, "library_name_exists"))
            case _ => None
          }
        }
      }
    }

    def validateSlug(newSlugOpt: Option[String], newSpace: LibrarySpace): Option[LibraryFail] = {
      newSlugOpt.flatMap { slugStr =>
        if (!LibrarySlug.isValidSlug(slugStr)) {
          Some(LibraryFail(BAD_REQUEST, "invalid_slug"))
        } else if (LibrarySlug.isReservedSlug(slugStr)) {
          Some(LibraryFail(BAD_REQUEST, "reserved_slug"))
        } else {
          val slug = LibrarySlug(slugStr)
          db.readOnlyMaster { implicit s =>
            libraryRepo.getBySpaceAndSlug(newSpace, slug)
          } match {
            case Some(other) if other.id.get != library.id.get => Some(LibraryFail(BAD_REQUEST, "library_slug_exists"))
            case _ => None
          }
        }
      }
    }

    def validateVisibility(newVisibilityOpt: Option[LibraryVisibility], newSpace: LibrarySpace): Option[LibraryFail] = {
      newVisibilityOpt.flatMap { newVisibility =>
        newSpace match {
          case _: UserSpace if newVisibility == LibraryVisibility.ORGANIZATION => Some(LibraryFail(BAD_REQUEST, "invalid_visibility"))
          case _ => None
        }
      }
    }

    val newSpace = modifyReq.space.getOrElse(library.space)
    val errorOpts = Stream(
      validateUserWritePermission,
      validateSpace(modifyReq.space),
      validateName(modifyReq.name, newSpace),
      validateSlug(modifyReq.slug, newSpace),
      validateVisibility(modifyReq.visibility, newSpace)
    )
    errorOpts.flatten.headOption
  }
  def modifyLibrary(libraryId: Id[Library], userId: Id[User], modifyReq: LibraryModifyRequest)(implicit context: HeimdalContext): Either[LibraryFail, LibraryModifyResponse] = {
    val library = db.readOnlyMaster { implicit s =>
      libraryRepo.get(libraryId)
    }

    // TODO(ryan): I hate that we have random stuff like LibraryMembership.listed being mutated in `modifyLibrary`
    // If you can figure out a better way to separate this out, I'd be thrilled
    db.readWrite { implicit session =>
      val membershipOpt = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
      (membershipOpt, modifyReq.listed) match {
        case (Some(membership), Some(newListed)) if newListed != membership.listed =>
          libraryMembershipRepo.save(membership.withListed(newListed))
        case _ =>
      }
    }

    validateModifyRequest(library, userId, modifyReq) match {
      case Some(error) => Left(error)
      case None =>
        val modifyResponse = unsafeModifyLibrary(library, modifyReq)
        Future {
          libraryAnalytics.editLibrary(userId, modifyResponse.modifiedLibrary, context, None, modifyResponse.edits)
          searchClient.updateLibraryIndex()
        }
        Right(modifyResponse)
    }
  }

  def unsafeModifyLibrary(library: Library, modifyReq: LibraryModifyRequest): LibraryModifyResponse = {
    val currentSpace = library.space
    val newSpace = modifyReq.space.getOrElse(currentSpace)

    val currentSlug = library.slug
    val newSlug = modifyReq.slug.map(LibrarySlug(_)).getOrElse(currentSlug)

    val newName = modifyReq.name.getOrElse(library.name)
    val newVisibility = modifyReq.visibility.getOrElse(library.visibility)

    val newSubKeysOpt = modifyReq.subscriptions
    val newDescription = modifyReq.description.orElse(library.description)
    val newColor = modifyReq.color.orElse(library.color)
    val newInviteToCollab = modifyReq.whoCanInvite.orElse(library.whoCanInvite)

    // New library subscriptions
    newSubKeysOpt match {
      case Some(newSubKeys) => db.readWrite { implicit s =>
        librarySubscriptionCommander.updateSubsByLibIdAndKey(library.id.get, newSubKeys)
      }
      case None =>
    }

    val modifiedLibrary = db.readWrite { implicit s =>
      if (newSpace != currentSpace || newSlug != currentSlug) {
        libraryAliasRepo.reclaim(newSpace, newSlug) // There is now a real library there; dump the alias
        libraryAliasRepo.alias(currentSpace, library.slug, library.id.get) // Make a new alias for where library used to live
      }

      val newOrgId = newSpace match {
        case OrganizationSpace(orgId) => Some(orgId)
        case UserSpace(_) => None
      }

      libraryRepo.save(library.copy(name = newName, slug = newSlug, visibility = newVisibility, description = newDescription, color = newColor, whoCanInvite = newInviteToCollab, state = LibraryStates.ACTIVE, organizationId = newOrgId))
    }

    // Update visibility of keeps
    // TODO(ryan): Change this method so that it operates exclusively on KTLs. Keeps should not have visibility anymore
    def updateKeepVisibility(changedVisibility: LibraryVisibility, iter: Int): Future[Unit] = Future {
      val (keeps, lib, curViz) = db.readOnlyMaster { implicit s =>
        val lib = libraryRepo.get(library.id.get)
        val viz = lib.visibility // It may have changed, re-check
        val keeps = keepRepo.getByLibraryIdAndExcludingVisibility(lib.id.get, Some(viz), 1000)
        (keeps, lib, viz)
      }
      if (keeps.nonEmpty && curViz == changedVisibility) {
        db.readWriteBatch(keeps, attempts = 5) { (s, k) =>
          implicit val session: RWSession = s
          keepCommander.syncWithLibrary(k, lib)
        }
        if (iter < 200) {
          // to prevent infinite loops if there's an issue updating keeps.
          updateKeepVisibility(changedVisibility, iter + 1)
        } else {
          val msg = s"[updateKeepVisibility] Problems updating visibility on ${lib.id.get} to $curViz, $iter"
          airbrake.notify(msg)
          Future.failed(new Exception(msg))
        }
      } else {
        Future.successful(())
      }
    }.flatMap(x => x)

    val keepChanges = updateKeepVisibility(newVisibility, 0)
    keepChanges.onComplete { _ => searchClient.updateKeepIndex() }

    val edits = Map(
      "title" -> (newName != library.name),
      "slug" -> (newSlug != library.slug),
      "description" -> (newDescription != library.description),
      "color" -> (newColor != library.color),
      "madePrivate" -> (newVisibility != library.visibility && newVisibility == LibraryVisibility.SECRET),
      "listed" -> modifyReq.listed.isDefined,
      "inviteToCollab" -> (newInviteToCollab != library.whoCanInvite),
      "space" -> (newSpace != library.space)
    )

    LibraryModifyResponse(modifiedLibrary, keepChanges, edits)
  }

  def deleteLibrary(libraryId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Option[LibraryFail] = {
    val oldLibrary = db.readOnlyMaster { implicit s => libraryRepo.get(libraryId) }
    if (oldLibrary.ownerId != userId) {
      Some(LibraryFail(FORBIDDEN, "permission_denied"))
    } else if (oldLibrary.kind == LibraryKind.SYSTEM_MAIN || oldLibrary.kind == LibraryKind.SYSTEM_SECRET) {
      Some(LibraryFail(BAD_REQUEST, "cant_delete_system_generated_library"))
    } else {
      val keepsInLibrary = db.readWrite { implicit s =>
        libraryMembershipRepo.getWithLibraryId(oldLibrary.id.get).foreach { m =>
          libraryMembershipRepo.save(m.withState(LibraryMembershipStates.INACTIVE))
        }
        libraryInviteRepo.getWithLibraryId(oldLibrary.id.get).foreach { inv =>
          libraryInviteRepo.save(inv.withState(LibraryInviteStates.INACTIVE))
        }
        keepRepo.getByLibrary(oldLibrary.id.get, 0, Int.MaxValue)
      }
      val savedKeeps = db.readWriteBatch(keepsInLibrary) { (s, keep) =>
        // ktlCommander.removeKeepFromLibrary(keep.id.get, libraryId)(s)
        keepCommander.deactivateKeep(keep)(s) // TODO(ryan): At some point, remove this code. Keeps should only be detached from libraries
      }
      libraryAnalytics.deleteLibrary(userId, oldLibrary, context)
      libraryAnalytics.unkeptPages(userId, savedKeeps.keySet.toSeq, oldLibrary, context)
      searchClient.updateKeepIndex()
      //Note that this is at the end, if there was an error while cleaning other library assets
      //we would want to be able to get back to the library and clean it again
      log.info(s"[zombieLibrary] Deleting lib: $oldLibrary")
      db.readWrite(attempts = 2) { implicit s =>
        libraryRepo.save(oldLibrary.sanitizeForDelete)
          .tap { l => log.info(s"[zombieLibrary] Should have deleted lib: $l") }
      }
      db.readOnlyMaster { implicit s =>
        libraryRepo.get(oldLibrary.id.get) match {
          case library if library.state == LibraryStates.ACTIVE => log.error(s"[zombieLibrary] Did not delete lib: $library")
          case library => log.info(s"[zombieLibrary] Successfully deleted lib: $library")
        }
      }
      searchClient.updateLibraryIndex()
      None
    }
  }

  def unsafeTransferLibrary(libId: Id[Library], newOwner: Id[User]): Library = {
    db.readWrite { implicit s =>
      val owner = userRepo.get(newOwner)
      assert(owner.state == UserStates.ACTIVE)

      val lib = libraryRepo.getNoCache(libId)

      libraryMembershipRepo.getWithLibraryIdAndUserId(libId, lib.ownerId).foreach { oldOwnerMembership =>
        libraryMembershipRepo.save(oldOwnerMembership.withState(LibraryMembershipStates.INACTIVE))
      }
      val existingMembershipOpt = libraryMembershipRepo.getWithLibraryIdAndUserId(libId, newOwner)
      val newMembershipTemplate = LibraryMembership(libraryId = libId, userId = newOwner, access = LibraryAccess.OWNER)
      libraryMembershipRepo.save(newMembershipTemplate.copy(id = existingMembershipOpt.map(_.id.get)))
      libraryRepo.save(lib.withOwner(newOwner))
    }
  }

  def canMoveTo(userId: Id[User], libId: Id[Library], to: LibrarySpace): Boolean = {
    val userCanModifyLibrary = canModifyLibrary(libId, userId)

    val (canMoveFromSpace, canMoveToSpace) = db.readOnlyMaster { implicit session =>
      val library = libraryRepo.get(libId)
      val from: LibrarySpace = library.space
      val canMoveFromSpace = from match {
        case OrganizationSpace(fromOrg) =>
          val fromPermissions = orgMembershipCommander.getPermissionsHelper(fromOrg, Some(userId))
          fromPermissions.contains(FORCE_EDIT_LIBRARIES) || (userId == library.ownerId && fromPermissions.contains(REMOVE_LIBRARIES))
        case UserSpace(fromUser) => userId == library.ownerId
      }
      val canMoveToSpace = to match {
        case OrganizationSpace(toOrg) => orgMembershipCommander.getPermissions(toOrg, Some(userId)).contains(ADD_LIBRARIES)
        case UserSpace(toUser) => toUser == library.ownerId
      }
      (canMoveFromSpace, canMoveToSpace)
    }

    userCanModifyLibrary && canMoveFromSpace && canMoveToSpace
  }

  def createReadItLaterLibrary(userId: Id[User]): Library = db.readWrite(attempts = 3) { implicit s =>
    val readItLaterLib = libraryRepo.save(Library(name = "Read It Later", ownerId = userId, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("read_id_later"), kind = LibraryKind.SYSTEM_READ_IT_LATER, memberCount = 1, keepCount = 0))
    libraryMembershipRepo.save(LibraryMembership(libraryId = readItLaterLib.id.get, userId = userId, access = LibraryAccess.OWNER))
    searchClient.updateLibraryIndex()
    readItLaterLib
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
    elizaClient.sendGlobalNotification( //push sent
      userIds = Set(lib.ownerId),
      title = title,
      body = message,
      linkText = s"See ${follower.firstName}’s profile",
      linkUrl = s"https://www.kifi.com/${follower.username.value}",
      imageUrl = s3ImageStore.avatarUrlByUser(follower),
      sticky = false,
      category = category,
      unread = !lotsOfFollowers, // if not a lot of recent followers, notification is marked unread
      extra = Some(Json.obj(
        "follower" -> BasicUser.fromUser(follower),
        "library" -> Json.toJson(LibraryNotificationInfoBuilder.fromLibraryAndOwner(lib, libImageOpt, owner))
      ))
    ) map { _ =>
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
    } else {
      val maxAccess = if (inviteList.isEmpty) LibraryAccess.READ_ONLY else inviteList.max.access
      val (updatedLib, updatedMem) = db.readWrite(attempts = 3) { implicit s =>
        val updatedMem = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId, None) match {
          case None =>
            val subscribedToUpdates = subscribed.getOrElse(maxAccess == LibraryAccess.READ_WRITE)
            log.info(s"[joinLibrary] New membership for $userId. New access: $maxAccess. $inviteList")
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
          convertKeepOwnershipToLibraryOwner(userId, lib)
          libraryAnalytics.unfollowLibrary(userId, lib, eventContext)
          searchClient.updateLibraryIndex()
          if (LibraryAccess.collaborativePermissions.contains(mem.access)) {
            refreshLibraryCollaboratorsTypeahead(libraryId)
          }
        }
        Right((): Unit)
    }
  }

  // TODO(ryan): is this actually necessary anymore? Check with Léo to see if Search needs it
  // We may not need to have this concept of "keep ownership" anymore. You can be the author of
  // a keep, which is its own thing. You can have access to edit a keep, which is also its own
  // thing.
  private def convertKeepOwnershipToLibraryOwner(userId: Id[User], library: Library) = {
    db.readWrite { implicit s =>
      keepRepo.getByUserIdAndLibraryId(userId, library.id.get).foreach { keep =>
        keepCommander.changeOwner(keep, library.ownerId)
      }
      ktlRepo.getByUserIdAndLibraryId(userId, library.id.get).foreach {
        ktlCommander.changeOwner(_, library.ownerId)
      }
    }
  }

  def copyKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag)(implicit context: HeimdalContext): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])] = {
    db.readOnlyMaster { implicit s =>
      collectionRepo.getByUserAndName(userId, tagName)
    } match {
      case None =>
        Left(LibraryFail(NOT_FOUND, "tag_not_found"))
      case Some(tag) =>
        val keeps = db.readOnlyMaster { implicit s =>
          keepToCollectionRepo.getKeepsForTag(tag.id.get).map { kId => keepRepo.get(kId) }
        }
        Right(copyKeeps(userId, libraryId, keeps.toSet, withSource = Some(KeepSource.tagImport)))
    }
  }

  def moveKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag)(implicit context: HeimdalContext): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])] = {
    db.readOnlyMaster { implicit s =>
      collectionRepo.getByUserAndName(userId, tagName)
    } match {
      case None =>
        Left(LibraryFail(NOT_FOUND, "tag_not_found"))
      case Some(tag) =>
        val keeps = db.readOnlyMaster { implicit s =>
          keepToCollectionRepo.getKeepsForTag(tag.id.get).map { kId => keepRepo.get(kId) }
        }
        Right(moveKeeps(userId, libraryId, keeps))
    }
  }

  def moveAllKeepsFromLibrary(userId: Id[User], fromLibraryId: Id[Library], toLibraryId: Id[Library])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)]) = {
    val keeps = db.readOnlyReplica { implicit session =>
      val keepIds = ktlRepo.getAllByLibraryId(fromLibraryId).map(_.keepId).toSet
      keepRepo.getByIds(keepIds).values.toSeq
    }
    moveKeeps(userId, toLibraryId, keeps)
  }

  def moveKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Seq[Keep])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)]) = {
    db.readWrite { implicit s =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(toLibraryId, userId) match {
        case Some(membership) if membership.canWrite => {
          val toLibrary = libraryRepo.get(toLibraryId)
          val validSourceLibraryIds = keeps.flatMap(_.libraryId).toSet.filter { fromLibraryId =>
            libraryMembershipRepo.getWithLibraryIdAndUserId(fromLibraryId, userId).exists(_.canWrite)
          }
          val failures = collection.mutable.ListBuffer[(Keep, LibraryError)]()
          val successes = collection.mutable.ListBuffer[Keep]()

          keeps.foreach {
            case keep if keep.libraryId.exists(validSourceLibraryIds.contains) => keepCommander.moveKeep(keep, toLibrary, userId)(s) match {
              case Right(movedKeep) => successes += movedKeep
              case Left(error) => failures += (keep -> error)
            }
            case forbiddenKeep => failures += (forbiddenKeep -> LibraryError.SourcePermissionDenied)
          }

          if (successes.nonEmpty) {
            libraryRepo.updateLastKept(toLibraryId)
            refreshKeepCounts(validSourceLibraryIds + toLibraryId)
            s.onTransactionSuccess {
              SafeFuture {
                searchClient.updateKeepIndex()
                libraryAnalytics.editLibrary(userId, toLibrary, context, Some("move_keeps"))
              }
            }
          }

          (successes.toSeq, failures.toSeq)
        }
        case _ => (Seq.empty[Keep], keeps.map(_ -> LibraryError.DestPermissionDenied))
      }
    }
  }

  def copyKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Set[Keep], withSource: Option[KeepSource])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)]) = {
    val sortedKeeps = keeps.toSeq.sortBy(keep => (keep.keptAt, keep.id.get))
    db.readWrite { implicit s =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(toLibraryId, userId) match {
        case Some(membership) if membership.canWrite => {
          val toLibrary = libraryRepo.get(toLibraryId)
          val validSourceLibraryIds = sortedKeeps.flatMap(_.libraryId).toSet.filter { fromLibraryId =>
            libraryMembershipRepo.getWithLibraryIdAndUserId(fromLibraryId, userId).isDefined
          }
          val failures = collection.mutable.ListBuffer[(Keep, LibraryError)]()
          val successes = collection.mutable.ListBuffer[Keep]()

          sortedKeeps.foreach {
            case keep if keep.libraryId.exists(validSourceLibraryIds.contains) => keepCommander.copyKeep(keep, toLibrary, userId, withSource)(s) match {
              case Right(movedKeep) => successes += movedKeep
              case Left(error) => failures += (keep -> error)
            }
            case forbiddenKeep => failures += (forbiddenKeep -> LibraryError.SourcePermissionDenied)
          }

          if (successes.nonEmpty) {
            libraryRepo.updateLastKept(toLibraryId)
            refreshKeepCounts(Set(toLibraryId))
            s.onTransactionSuccess {
              SafeFuture {
                searchClient.updateKeepIndex()
                libraryAnalytics.editLibrary(userId, toLibrary, context, Some("copy_keeps"))
              }
            }
          }
          (successes.toSeq, failures.toSeq)
        }
        case _ => (Seq.empty[Keep], sortedKeeps.map(_ -> LibraryError.DestPermissionDenied))
      }
    }
  }

  private def refreshKeepCounts(libraryIds: Set[Id[Library]])(implicit session: RWSession): Unit = {
    libraryIds.foreach(libraryId => countByLibraryCache.remove(CountByLibraryKey(libraryId)))
    keepRepo.getCountsByLibrary(libraryIds).foreach {
      case (libraryId, keepCount) =>
        val library = libraryRepo.get(libraryId)
        libraryRepo.save(library.copy(keepCount = keepCount))
    }
  }

  def trackLibraryView(viewerId: Option[Id[User]], library: Library)(implicit context: HeimdalContext): Unit = {
    libraryAnalytics.viewedLibrary(viewerId, library, context)
  }

  def updateLastEmailSent(userId: Id[User], keeps: Seq[Keep]): Unit = {
    // persist when we last sent an email for each library membership
    db.readWrite { implicit rw =>
      keeps.groupBy(_.libraryId).collect { case (Some(libId), _) => libId } foreach { libId =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libId, userId) map { libMembership =>
          libraryMembershipRepo.updateLastEmailSent(libMembership.id.get)
        }
      }
    }
  }

  def updateSubscribedToLibrary(userId: Id[User], libraryId: Id[Library], subscribedToUpdatesNew: Boolean): Either[LibraryFail, LibraryMembership] = {
    db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
    } match {
      case None => Left(LibraryFail(NOT_FOUND, "need_to_follow_to_subscribe"))
      case Some(mem) if mem.subscribedToUpdates == subscribedToUpdatesNew => Right(mem)
      case Some(mem) => {
        val updatedMembership = db.readWrite { implicit s =>
          libraryMembershipRepo.save(mem.copy(subscribedToUpdates = subscribedToUpdatesNew))
        }
        Right(updatedMembership)
      }
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
            (requesterMem.isFollower && requesterMem.userId == targetMem.userId)) { // a follower can only edit herself
            db.readWrite { implicit s =>
              newAccess match {
                case None =>
                  SafeFuture { convertKeepOwnershipToLibraryOwner(targetMem.userId, library) }
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
}
