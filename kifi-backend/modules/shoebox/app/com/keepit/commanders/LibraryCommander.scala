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
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model.OrganizationPermission.{ ADD_LIBRARIES, REMOVE_LIBRARIES, FORCE_EDIT_LIBRARIES }
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.kifi.macros.json
import play.api.http.Status._

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
  def createLibrary(libCreateReq: LibraryCreateRequest, ownerId: Id[User])(implicit context: HeimdalContext): Either[LibraryFail, Library]
  def modifyLibrary(libraryId: Id[Library], userId: Id[User], modifyReq: LibraryModifyRequest)(implicit context: HeimdalContext): Either[LibraryFail, LibraryModifyResponse]
  def unsafeModifyLibrary(library: Library, modifyReq: LibraryModifyRequest): LibraryModifyResponse
  def deleteLibrary(libraryId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Option[LibraryFail]
  def createReadItLaterLibrary(userId: Id[User]): Library
  def copyKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag)(implicit context: HeimdalContext): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])]
  def moveKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag)(implicit context: HeimdalContext): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])]
  def copyKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Set[Keep], withSource: Option[KeepSource])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)])
  def moveKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Seq[Keep])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)])
  def moveAllKeepsFromLibrary(userId: Id[User], fromLibraryId: Id[Library], toLibraryId: Id[Library])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)])
  def trackLibraryView(viewerId: Option[Id[User]], library: Library)(implicit context: HeimdalContext): Unit
  def updateLastEmailSent(userId: Id[User], keeps: Seq[Keep]): Unit
  def updateSubscribedToLibrary(userId: Id[User], libraryId: Id[Library], subscribedToUpdatesNew: Boolean): Either[LibraryFail, LibraryMembership]
  def unsafeTransferLibrary(libraryId: Id[Library], newOwner: Id[User])(implicit session: RWSession): Library
  def unsafeAsyncDeleteLibrary(libraryId: Id[Library]): Future[Unit]
}

class LibraryCommanderImpl @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryAliasRepo: LibraryAliasRepo,
    libraryInviteRepo: LibraryInviteRepo,
    librarySubscriptionCommander: LibrarySubscriptionCommander,
    permissionCommander: PermissionCommander,
    libraryAccessCommander: LibraryAccessCommander,
    userRepo: UserRepo,
    keepRepo: KeepRepo,
    keepCommander: KeepCommander,
    keepToCollectionRepo: KeepToCollectionRepo,
    ktlRepo: KeepToLibraryRepo,
    ktlCommander: KeepToLibraryCommander,
    countByLibraryCache: CountByLibraryCache,
    collectionRepo: CollectionRepo,
    airbrake: AirbrakeNotifier,
    searchClient: SearchServiceClient,
    libraryAnalytics: LibraryAnalytics,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration,
    clock: Clock) extends LibraryCommander with Logging {

  def updateLastView(userId: Id[User], libraryId: Id[Library]): Unit = {
    Future {
      db.readWrite { implicit s =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId).foreach { mem =>
          libraryMembershipRepo.updateLastViewed(mem.id.get) // do not update seq num
        }
      }
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
              val permissions = permissionCommander.getOrganizationPermissions(orgId, Some(ownerId))
              permissions.contains(OrganizationPermission.ADD_LIBRARIES) &&
                (libCreateReq.visibility != LibraryVisibility.PUBLISHED || permissions.contains(OrganizationPermission.PUBLISH_LIBRARIES))
            case UserSpace(userId) =>
              userId == ownerId // Right now this is guaranteed to be correct, could replace with true
          }
          val sameSlugOpt = libraryRepo.getBySpaceAndSlug(targetSpace, validSlug)

          (userHasPermissionToCreateInSpace, sameSlugOpt)
        } match {
          case (false, _) =>
            Left(LibraryFail(FORBIDDEN, "cannot_add_library_to_space"))
          case (_, Some(sameSlug)) =>
            Left(LibraryFail(BAD_REQUEST, "library_slug_exists"))
          case (_, None) =>
            val newColor = libCreateReq.color.orElse(Some(LibraryColor.pickRandomLibraryColor()))
            val newListed = libCreateReq.listed.getOrElse(true)
            val newKind = libCreateReq.kind.getOrElse(LibraryKind.USER_CREATED)
            val newInviteToCollab = libCreateReq.whoCanInvite.orElse(Some(LibraryInvitePermissions.COLLABORATOR))
            val newOrgMemberAccessOpt = orgIdOpt.map(_ => libCreateReq.orgMemberAccess.getOrElse(LibraryAccess.READ_WRITE)) // Paid feature?

            val library = db.readWrite { implicit s =>
              libraryAliasRepo.reclaim(targetSpace, validSlug) // there's gonna be a real library there, dump the alias
              libraryRepo.getBySpaceAndSlug(targetSpace, validSlug, excludeState = None) match {
                case None =>
                  val lib = libraryRepo.save(Library(ownerId = ownerId, name = libCreateReq.name, description = libCreateReq.description,
                    visibility = libCreateReq.visibility, slug = validSlug, color = newColor, kind = newKind,
                    memberCount = 1, keepCount = 0, whoCanInvite = newInviteToCollab, organizationId = orgIdOpt, organizationMemberAccess = newOrgMemberAccessOpt))
                  libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = ownerId, access = LibraryAccess.OWNER, listed = newListed, lastJoinedAt = Some(currentDateTime)))
                  libCreateReq.subscriptions match {
                    case Some(subKeys) => librarySubscriptionCommander.updateSubsByLibIdAndKey(lib.id.get, subKeys)
                    case None =>
                  }
                  lib
                case Some(lib) =>
                  val newLib = libraryRepo.save(Library(id = lib.id, ownerId = ownerId,
                    name = libCreateReq.name, description = libCreateReq.description, visibility = libCreateReq.visibility, slug = validSlug, color = newColor, kind = newKind,
                    memberCount = 1, keepCount = 0, whoCanInvite = newInviteToCollab, organizationId = orgIdOpt, organizationMemberAccess = newOrgMemberAccessOpt))
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

  def validateModifyRequest(library: Library, userId: Id[User], modifyReq: LibraryModifyRequest): Option[LibraryFail] = {
    def validateUserWritePermission: Option[LibraryFail] = {
      if (libraryAccessCommander.canModifyLibrary(library.id.get, userId)) None
      else Some(LibraryFail(FORBIDDEN, "permission_denied"))
    }

    def validateSpace(newSpaceOpt: Option[LibrarySpace]): Option[LibraryFail] = {
      newSpaceOpt.flatMap { newSpace =>
        if (!libraryAccessCommander.canMoveTo(userId = userId, libId = library.id.get, to = newSpace)) Some(LibraryFail(BAD_REQUEST, "invalid_space"))
        else None
      }
    }

    def validateName(newNameOpt: Option[String], newSpace: LibrarySpace): Option[LibraryFail] = {
      newNameOpt.flatMap { name =>
        if (!Library.isValidName(name)) {
          Some(LibraryFail(BAD_REQUEST, "invalid_name"))
        } else None
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
      (newVisibilityOpt, newSpace) match {
        case (Some(LibraryVisibility.ORGANIZATION), _: UserSpace) => Some(LibraryFail(BAD_REQUEST, "invalid_visibility"))
        case (Some(LibraryVisibility.PUBLISHED), OrganizationSpace(orgId)) if db.readOnlyReplica { implicit s => !permissionCommander.getOrganizationPermissions(orgId, Some(userId)).contains(OrganizationPermission.PUBLISH_LIBRARIES) } =>
          Some(LibraryFail(FORBIDDEN, "publish_libraries"))
        case _ => None
      }
    }

    def validateIntegrationPermissions(newSubscriptions: Option[Seq[LibrarySubscriptionKey]], newSpace: LibrarySpace): Option[LibraryFail] = {
      db.readOnlyReplica { implicit session =>
        (newSubscriptions.exists(_.nonEmpty), newSpace) match {
          case (true, space: OrganizationSpace) if !permissionCommander.getOrganizationPermissions(space.id, Some(userId)).contains(OrganizationPermission.CREATE_SLACK_INTEGRATION) =>
            Some(LibraryFail(FORBIDDEN, "create_slack_integration"))
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
      validateVisibility(modifyReq.visibility, newSpace),
      validateIntegrationPermissions(modifyReq.subscriptions, newSpace)
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
    val newOrgIdOpt = newSpace match {
      case OrganizationSpace(orgId) => Some(orgId)
      case UserSpace(_) => None
    }

    val currentSlug = library.slug
    val newSlug = modifyReq.slug.map(LibrarySlug(_)).getOrElse(currentSlug)

    val newName = modifyReq.name.getOrElse(library.name)
    val newVisibility = modifyReq.visibility.getOrElse(library.visibility)

    val newSubKeysOpt = modifyReq.subscriptions
    val newDescription = modifyReq.description.orElse(library.description)
    val newColor = modifyReq.color.orElse(library.color)
    val newInviteToCollab = modifyReq.whoCanInvite.orElse(library.whoCanInvite)
    val newOrgMemberAccessOpt = newOrgIdOpt match {
      case Some(orgId) => Some(modifyReq.orgMemberAccess orElse library.organizationMemberAccess getOrElse LibraryAccess.READ_WRITE)
      case None => library.organizationMemberAccess
    }

    // New library subscriptions
    newSubKeysOpt.foreach { newSubKeys =>
      db.readWrite { implicit s =>
        librarySubscriptionCommander.updateSubsByLibIdAndKey(library.id.get, newSubKeys)
      }
    }

    val modifiedLibrary = db.readWrite { implicit s =>
      if (newSpace != currentSpace || newSlug != currentSlug) {
        libraryAliasRepo.reclaim(newSpace, newSlug) // There is now a real library there; dump the alias
        libraryAliasRepo.alias(currentSpace, library.slug, library.id.get) // Make a new alias for where library used to live
      }

      libraryRepo.save(library.copy(name = newName, slug = newSlug, visibility = newVisibility, description = newDescription, color = newColor, whoCanInvite = newInviteToCollab, state = LibraryStates.ACTIVE, organizationId = newOrgIdOpt, organizationMemberAccess = newOrgMemberAccessOpt))
    }

    // Update visibility of keeps
    // TODO(ryan): Change this method so that it operates exclusively on KTLs. Keeps should not have visibility anymore
    def updateKeepVisibility(changedVisibility: LibraryVisibility, iter: Int): Future[Unit] = Future {
      val (keeps, lib, curViz) = db.readOnlyMaster { implicit s =>
        val lib = libraryRepo.get(library.id.get)
        val viz = lib.visibility // It may have changed, re-check
        val keepIds = keepRepo.getByLibraryIdAndExcludingVisibility(lib.id.get, Some(viz), 500).map(_.id.get).toSet ++ keepRepo.getByLibraryWithInconsistentOrgId(lib.id.get, lib.organizationId, Limit(500))
        val keeps = keepRepo.getByIds(keepIds).values.toSeq
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

  def unsafeAsyncDeleteLibrary(libraryId: Id[Library]): Future[Unit] = { // used to manually delete libraries
    val deletedMembersFut = db.readWriteAsync { implicit session =>
      libraryMembershipRepo.getWithLibraryId(libraryId).foreach { mem =>
        libraryMembershipRepo.save(mem.withState(LibraryMembershipStates.INACTIVE))
      }
    }

    val deletedInvitesFut = db.readWriteAsync { implicit session =>
      libraryInviteRepo.getWithLibraryId(libraryId).foreach { invite =>
        libraryInviteRepo.save(invite.withState(LibraryInviteStates.INACTIVE))
      }
    }

    val deletedKeepsFut = db.readWriteAsync { implicit session =>
      keepRepo.getByLibrary(libraryId, 0, Int.MaxValue).foreach(keepCommander.deactivateKeep)
      searchClient.updateKeepIndex()
    }

    for {
      deletedMembers <- deletedMembersFut
      deletedInvites <- deletedInvitesFut
      deletedKeeps <- deletedKeepsFut
    } yield {
      db.readWriteAsync { implicit session =>
        libraryRepo.save(libraryRepo.get(libraryId).withState(LibraryStates.INACTIVE))
        searchClient.updateLibraryIndex()
      }
    }
  }

  def unsafeTransferLibrary(libraryId: Id[Library], newOwner: Id[User])(implicit session: RWSession): Library = {
    val owner = userRepo.get(newOwner)
    assert(owner.state == UserStates.ACTIVE)

    val lib = libraryRepo.getNoCache(libraryId)

    libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, lib.ownerId).foreach { oldOwnerMembership =>
      libraryMembershipRepo.save(oldOwnerMembership.withState(LibraryMembershipStates.INACTIVE))
    }
    val existingMembershipOpt = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, newOwner)
    val newMembershipTemplate = LibraryMembership(libraryId = libraryId, userId = newOwner, access = LibraryAccess.OWNER)
    libraryMembershipRepo.save(newMembershipTemplate.copy(id = existingMembershipOpt.map(_.id.get)))
    libraryRepo.save(lib.withOwner(newOwner))
  }

  def createReadItLaterLibrary(userId: Id[User]): Library = db.readWrite(attempts = 3) { implicit s =>
    val readItLaterLib = libraryRepo.save(Library(name = "Read It Later", ownerId = userId, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("read_id_later"), kind = LibraryKind.SYSTEM_READ_IT_LATER, memberCount = 1, keepCount = 0))
    libraryMembershipRepo.save(LibraryMembership(libraryId = readItLaterLib.id.get, userId = userId, access = LibraryAccess.OWNER))
    searchClient.updateLibraryIndex()
    readItLaterLib
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
        case Some(membership) if membership.canWrite =>
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
      case Some(mem) =>
        val updatedMembership = db.readWrite { implicit s =>
          libraryMembershipRepo.save(mem.copy(subscribedToUpdates = subscribedToUpdatesNew))
        }
        Right(updatedMembership)
    }
  }
}

protected object LibraryCommanderImpl {
  val slugPrefixRegex = """^(.*)-\d+$""".r
}
