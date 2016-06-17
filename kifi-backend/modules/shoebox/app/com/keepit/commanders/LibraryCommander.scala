package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ Logging, SlackLog }
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.slack.models.{ LibraryToSlackChannelRepo, SlackChannelToLibraryRepo }
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.keepit.typeahead.LibraryTypeahead
import com.kifi.macros.json
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import play.api.http.Status._

import scala.concurrent._

@json case class MarketingSuggestedLibrarySystemValue(
  id: Id[Library],
  caption: Option[String] = None)

object MarketingSuggestedLibrarySystemValue {
  // system value that persists the library IDs and additional library data for the marketing site
  def systemValueName = Name[SystemValue]("marketing_site_libraries")
}

@json case class LibraryUpdates(latestActivity: DateTime, updates: Int)

@ImplementedBy(classOf[LibraryCommanderImpl])
trait LibraryCommander {
  def updateLastView(userId: Id[User], libraryId: Id[Library]): Unit
  def createLibrary(libCreateReq: LibraryInitialValues, ownerId: Id[User])(implicit context: HeimdalContext): Either[LibraryFail, Library]
  def modifyLibrary(libraryId: Id[Library], userId: Id[User], modifyReq: LibraryModifications)(implicit context: HeimdalContext): Either[LibraryFail, LibraryModifyResponse]
  def deleteLibrary(libraryId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Option[LibraryFail]
  def internSystemGeneratedLibraries(userId: Id[User], generateNew: Boolean = true): (Library, Library)
  def copyKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag)(implicit context: HeimdalContext): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])]
  def moveKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag)(implicit context: HeimdalContext): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])]
  def copyKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Set[Keep], withSource: Option[KeepSource])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)])
  def moveKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Seq[Keep])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)])
  def moveAllKeepsFromLibrary(userId: Id[User], fromLibraryId: Id[Library], toLibraryId: Id[Library])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)])
  def trackLibraryView(viewerId: Option[Id[User]], library: Library)(implicit context: HeimdalContext): Unit
  def updateLastEmailSent(userId: Id[User], keeps: Seq[Keep]): Unit
  def updateSubscribedToLibrary(userId: Id[User], libraryId: Id[Library], subscribedToUpdatesNew: Boolean): Either[LibraryFail, LibraryMembership]
  def getUpdatesToLibrary(libraryId: Id[Library], since: DateTime): LibraryUpdates

  // These are "fast" methods, so they can be transactional
  def unsafeCreateLibrary(libCreateReq: LibraryInitialValues, ownerId: Id[User])(implicit session: RWSession): Library
  def unsafeTransferLibrary(libraryId: Id[Library], newOwner: Id[User])(implicit session: RWSession): Library

  // These methods take forever (they have to fiddle with values denormalized onto keeps) so they're async
  def unsafeModifyLibrary(library: Library, modifyReq: LibraryModifications): LibraryModifyResponse
  def unsafeAsyncDeleteLibrary(libraryId: Id[Library]): Future[Unit]
}

@Singleton
class LibraryCommanderImpl @Inject() (
  db: Database,
  libraryRepo: LibraryRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  libraryAliasRepo: LibraryAliasRepo,
  libraryInviteRepo: LibraryInviteRepo,
  slackChannelToLibraryRepo: SlackChannelToLibraryRepo,
  libraryToSlackChannelRepo: LibraryToSlackChannelRepo,
  permissionCommander: PermissionCommander,
  libraryAccessCommander: LibraryAccessCommander,
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  keepCommander: KeepCommander,
  keepSourceRepo: KeepSourceAttributionRepo,
  keepMutator: KeepMutator,
  ktlRepo: KeepToLibraryRepo,
  ktlCommander: KeepToLibraryCommander,
  countByLibraryCache: CountByLibraryCache,
  relevantSuggestedLibrariesCache: RelevantSuggestedLibrariesCache,
  airbrake: AirbrakeNotifier,
  searchClient: SearchServiceClient,
  libraryAnalytics: LibraryAnalytics,
  tagCommander: TagCommander,
  libraryTypeahead: LibraryTypeahead,
  libraryResultCache: LibraryResultCache,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val inhouseSlackClient: InhouseSlackClient,
  clock: Clock)
    extends LibraryCommander with Logging {
  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SHOEBOX)

  def updateLastView(userId: Id[User], libraryId: Id[Library]): Unit = {
    Future {
      db.readWrite { implicit s =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId).foreach { mem =>
          libraryMembershipRepo.updateLastViewed(mem.id.get) // do not update seq num
        }
      }
    }
  }

  def createLibrary(libCreateReq: LibraryInitialValues, ownerId: Id[User])(implicit context: HeimdalContext): Either[LibraryFail, Library] = {
    val validationError = db.readOnlyReplica { implicit session => validateCreateRequest(libCreateReq, ownerId) }
    validationError match {
      case Some(fail) => Left(fail)
      case None =>
        val library = db.readWrite { implicit session => unsafeCreateLibrary(libCreateReq, ownerId) }
        SafeFuture {
          libraryAnalytics.createLibrary(ownerId, library, context)
          searchClient.updateLibraryIndex()
          libraryTypeahead.refreshForAllCollaborators(library.id.get)
          relevantSuggestedLibrariesCache.direct.remove(RelevantSuggestedLibrariesKey(ownerId))
        }
        Right(library)
    }
  }

  def validateCreateRequest(libCreateReq: LibraryInitialValues, ownerId: Id[User])(implicit session: RSession): Option[LibraryFail] = {
    val targetSpace = libCreateReq.space.getOrElse(LibrarySpace.fromUserId(ownerId))

    def invalidName = libCreateReq.name match {
      case name if !Library.isValidName(name) => Some(LibraryFail(BAD_REQUEST, "invalid_name"))
      case _ => None
    }
    def invalidSlug = libCreateReq.slug.collect {
      case slug if !LibrarySlug.isValidSlug(slug) || LibrarySlug.isReservedSlug(slug) => LibraryFail(BAD_REQUEST, "invalid_slug")
    }
    def slugCollision = libCreateReq.slug.collect {
      case slug if libraryRepo.getBySpaceAndSlug(targetSpace, LibrarySlug(slug)).isDefined =>
        LibraryFail(BAD_REQUEST, "library_slug_exists")
    }
    def invalidSpace = {
      val canCreateLibraryInSpace = targetSpace match {
        case OrganizationSpace(orgId) =>
          permissionCommander.getOrganizationPermissions(orgId, Some(ownerId)).contains(OrganizationPermission.ADD_LIBRARIES)
        case UserSpace(userId) =>
          userId == ownerId // Right now this is guaranteed to be correct, could replace with true
      }
      if (!canCreateLibraryInSpace) Some(LibraryFail(FORBIDDEN, "cannot_add_library_to_space"))
      else None
    }
    def invalidVisibility = {
      (targetSpace, libCreateReq.visibility) match {
        case (UserSpace(_), LibraryVisibility.ORGANIZATION) =>
          Some(LibraryFail(BAD_REQUEST, "invalid_visibility"))
        case (OrganizationSpace(orgId), LibraryVisibility.PUBLISHED) if !permissionCommander.getOrganizationPermissions(orgId, Some(ownerId)).contains(OrganizationPermission.PUBLISH_LIBRARIES) =>
          Some(LibraryFail(FORBIDDEN, "cannot_publish_libraries_in_space"))
        case _ => None
      }
    }

    val error = Stream(
      invalidName,
      invalidSlug,
      slugCollision,
      invalidSpace,
      invalidVisibility
    ).flatten.headOption

    error tap {
      case Some(err) => slackLog.warn("Validation error!", err.message, "for request", libCreateReq.toString)
      case _ =>
    }
  }

  def unsafeCreateLibrary(libCreateReq: LibraryInitialValues, ownerId: Id[User])(implicit session: RWSession): Library = {
    val targetSpace = libCreateReq.space.getOrElse(LibrarySpace.fromUserId(ownerId))
    val orgIdOpt = targetSpace match {
      case UserSpace(_) => None
      case OrganizationSpace(orgId) => Some(orgId)
    }
    val newSlug = libCreateReq.slug match {
      case Some(slug) => LibrarySlug(slug)
      case None =>
        val suffixes = "" +: Stream.continually("-" + RandomStringUtils.randomNumeric(2)).take(9)
        val baseSlug = LibrarySlug.generateFromName(libCreateReq.name)
        suffixes.map(suff => LibrarySlug(baseSlug + suff)).filter { slug =>
          libraryRepo.getBySpaceAndSlug(targetSpace, slug).isEmpty
        }.head
    }
    val newColor = libCreateReq.color.orElse(Some(LibraryColor.pickRandomLibraryColor()))
    val newListed = libCreateReq.listed.getOrElse(true)
    val newKind = libCreateReq.kind.getOrElse(LibraryKind.USER_CREATED)
    val newInviteToCollab = libCreateReq.whoCanInvite.orElse(Some(LibraryInvitePermissions.COLLABORATOR))
    val newOrgMemberAccessOpt = orgIdOpt.map(_ => libCreateReq.orgMemberAccess.getOrElse(LibraryAccess.READ_WRITE)) // Paid feature?

    libraryAliasRepo.reclaim(targetSpace, newSlug) // there's gonna be a real library there, dump the alias
    val newLib = {
      val existingLibOpt = libraryRepo.getBySpaceAndSlug(targetSpace, newSlug, excludeState = None)
      val newLibrary = Library(
        ownerId = ownerId, name = libCreateReq.name, description = libCreateReq.description,
        visibility = libCreateReq.visibility, slug = newSlug, color = newColor, kind = newKind,
        memberCount = 1, keepCount = 0, whoCanInvite = newInviteToCollab, organizationId = orgIdOpt, organizationMemberAccess = newOrgMemberAccessOpt
      )
      libraryRepo.save(newLibrary.copy(id = existingLibOpt.flatMap(_.id)))
    }

    val ownerMembership = {
      val libraryId = newLib.id.get
      val existingMembershipOpt = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId = libraryId, userId = ownerId, None)
      val newMembership = LibraryMembership(libraryId = libraryId, userId = ownerId, access = LibraryAccess.OWNER, subscribedToUpdates = true, listed = newListed)
      libraryMembershipRepo.save(newMembership.copy(id = existingMembershipOpt.flatMap(_.id)))
    }

    newLib
  }

  def validateModifyRequest(library: Library, userId: Id[User], modifyReq: LibraryModifications): Option[LibraryFail] = {
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

    val newSpace = modifyReq.space.getOrElse(library.space)
    val oldSpace = library.space
    val errorOpts = Stream(
      validateUserWritePermission,
      validateSpace(modifyReq.space),
      validateName(modifyReq.name, newSpace),
      validateSlug(modifyReq.slug, newSpace),
      validateVisibility(modifyReq.visibility, newSpace)
    )
    errorOpts.flatten.headOption tap {
      case Some(err) => slackLog.warn("Validation error!", err.message, "for request", modifyReq.toString)
      case _ =>
    }
  }
  def modifyLibrary(libraryId: Id[Library], userId: Id[User], modifyReq: LibraryModifications)(implicit context: HeimdalContext): Either[LibraryFail, LibraryModifyResponse] = {
    val library = db.readOnlyMaster { implicit s =>
      libraryRepo.get(libraryId)
    }

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
          libraryResultCache.direct.remove(LibraryResultKey(userId, libraryId))
          libraryAnalytics.editLibrary(userId, modifyResponse.modifiedLibrary, context, None, modifyResponse.edits)
          searchClient.updateLibraryIndex()
        }
        Right(modifyResponse)
    }
  }

  def unsafeModifyLibrary(library: Library, modifyReq: LibraryModifications): LibraryModifyResponse = {
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

    val newDescription = modifyReq.description.orElse(library.description)
    val newColor = modifyReq.color.orElse(library.color)
    val newInviteToCollab = modifyReq.whoCanInvite.orElse(library.whoCanInvite)
    val newOrgMemberAccessOpt = newOrgIdOpt match {
      case Some(orgId) => Some(modifyReq.orgMemberAccess orElse library.organizationMemberAccess getOrElse LibraryAccess.READ_WRITE)
      case None => library.organizationMemberAccess
    }
    val newLibraryCommentPermissions = modifyReq.whoCanComment.getOrElse(library.whoCanComment)

    val modifiedLibrary = db.readWrite { implicit s =>
      if (newSpace != currentSpace || newSlug != currentSlug) {
        libraryAliasRepo.reclaim(newSpace, newSlug) // There is now a real library there; dump the alias
        libraryAliasRepo.alias(currentSpace, library.slug, library.id.get) // Make a new alias for where library used to live
      }

      libraryRepo.save(library.copy(
        name = newName, slug = newSlug, visibility = newVisibility, description = newDescription, color = newColor,
        whoCanInvite = newInviteToCollab, organizationId = newOrgIdOpt,
        organizationMemberAccess = newOrgMemberAccessOpt, whoCanComment = newLibraryCommentPermissions)
      )
    }

    if (newVisibility != library.visibility || newOrgMemberAccessOpt != library.organizationMemberAccess) {
      libraryTypeahead.refreshForAllCollaborators(library.id.get)
    }

    def updateKeepVisibility(changedVisibility: LibraryVisibility, iter: Int): Future[Unit] = Future {
      val (ktls, lib, curViz) = db.readOnlyMaster { implicit s =>
        val lib = libraryRepo.get(library.id.get)
        val viz = lib.visibility // It may have changed, re-check
        val ktls = {
          ktlRepo.getByLibraryWithInconsistentVisibility(lib.id.get, expectedVisibility = viz, Limit(500)) ++
            ktlRepo.getByLibraryWithInconsistentOrgId(lib.id.get, lib.organizationId, Limit(500))
        }
        (ktls, lib, viz)
      }
      if (ktls.nonEmpty && curViz == changedVisibility) {
        db.readWriteBatch(ktls, attempts = 5) { (s, ktl) =>
          ktlCommander.syncWithLibrary(ktl, lib)(s)
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
    val (oldLibrary, permissions) = db.readOnlyMaster { implicit s =>
      (libraryRepo.get(libraryId), permissionCommander.getLibraryPermissions(libraryId, Some(userId)))
    }
    if (!permissions.contains(LibraryPermission.DELETE_LIBRARY)) {
      Some(LibraryFail(FORBIDDEN, "permission_denied"))
    } else if (oldLibrary.isSystemLibrary) {
      Some(LibraryFail(BAD_REQUEST, "cant_delete_system_generated_library"))
    } else {
      val keepsInLibrary = db.readWrite { implicit s =>
        libraryMembershipRepo.getWithLibraryId(oldLibrary.id.get).foreach { m =>
          libraryMembershipRepo.save(m.withState(LibraryMembershipStates.INACTIVE))
        }
        libraryInviteRepo.getWithLibraryId(oldLibrary.id.get).foreach { inv =>
          libraryInviteRepo.save(inv.withState(LibraryInviteStates.INACTIVE))
        }
        slackChannelToLibraryRepo.getActiveByLibrary(libraryId).foreach { integration =>
          slackChannelToLibraryRepo.deactivate(integration)
        }
        libraryToSlackChannelRepo.getActiveByLibrary(libraryId).foreach { integration =>
          libraryToSlackChannelRepo.deactivate(integration)
        }

        keepRepo.pageByLibrary(oldLibrary.id.get, 0, Int.MaxValue)
      }
      val savedKeeps = db.readWriteBatch(keepsInLibrary) { (s, keep) =>
        // TODO[keepscussions]: Keeps should only be detached from libraries, not deactivated
        // ktlCommander.removeKeepFromLibrary(keep.id.get, libraryId)(s)
        keepMutator.deactivateKeep(keep)(s)
      }
      libraryAnalytics.deleteLibrary(userId, oldLibrary, context)
      libraryAnalytics.unkeptPages(userId, savedKeeps.keySet.toSeq, oldLibrary, context)
      searchClient.updateKeepIndex()
      //Note that this is at the end, if there was an error while cleaning other library assets
      //we would want to be able to get back to the library and clean it again
      log.info(s"[zombieLibrary] Deleting lib: $oldLibrary")
      db.readWrite(attempts = 2) { implicit s =>
        libraryRepo.deactivate(oldLibrary)
          .tap { l => log.info(s"[zombieLibrary] Should have deleted lib: $l") }
      }
      db.readOnlyMaster { implicit s =>
        libraryRepo.get(oldLibrary.id.get) match {
          case library if library.state == LibraryStates.ACTIVE => log.error(s"[zombieLibrary] Did not delete lib: $library")
          case library => log.info(s"[zombieLibrary] Successfully deleted lib: $library")
        }
      }

      libraryTypeahead.refreshForAllCollaborators(oldLibrary.id.get)
      libraryResultCache.direct.remove(LibraryResultKey(userId, libraryId))
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

    val deletedIntegrationsFut = db.readWriteAsync { implicit session =>
      slackChannelToLibraryRepo.getActiveByLibrary(libraryId).foreach { integration =>
        slackChannelToLibraryRepo.deactivate(integration)
      }
      libraryToSlackChannelRepo.getActiveByLibrary(libraryId).foreach { integration =>
        libraryToSlackChannelRepo.deactivate(integration)
      }
    }

    val deletedKeepsFut = db.readWriteAsync { implicit session =>
      keepRepo.pageByLibrary(libraryId, 0, Int.MaxValue).foreach(keepMutator.deactivateKeep)
      searchClient.updateKeepIndex()
    }

    for {
      deletedMembers <- deletedMembersFut
      deletedInvites <- deletedInvitesFut
      refreshedTypeahead <- libraryTypeahead.refreshForAllCollaborators(libraryId)
      deletedKeeps <- deletedKeepsFut
      deletedIntegrations <- deletedIntegrationsFut
    } yield {
      db.readWrite { implicit session =>
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
      libraryMembershipRepo.save(oldOwnerMembership.withAccess(LibraryAccess.READ_WRITE))
    }

    val ownerMembership = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId = libraryId, userId = newOwner, None) match {
      case Some(membership) if membership.isActive =>
        val updatedMembership = membership.copy(access = LibraryAccess.OWNER, subscribedToUpdates = true)
        if (updatedMembership == membership) membership else libraryMembershipRepo.save(updatedMembership)
      case inactiveMembershipOpt =>
        val newMembership = LibraryMembership(libraryId = libraryId, userId = newOwner, access = LibraryAccess.OWNER, subscribedToUpdates = true)
        libraryMembershipRepo.save(newMembership.copy(id = inactiveMembershipOpt.flatMap(_.id)))
    }

    libraryRepo.save(lib.withOwner(newOwner))
  }

  def internSystemGeneratedLibraries(userId: Id[User], generateNew: Boolean = true): (Library, Library) = {
    db.readWrite(attempts = 3) { implicit session =>
      val libMem = libraryMembershipRepo.getWithUserId(userId, None)
      val allLibs = libraryRepo.getByUser(userId, None)
      val user = userRepo.get(userId)

      // Get all current system libraries, for main/secret, make sure only one is active.
      // This corrects any issues with previously created libraries / memberships
      val sysLibs = allLibs.filter(_._2.ownerId == userId)
        .filter(l => l._2.kind == LibraryKind.SYSTEM_MAIN || l._2.kind == LibraryKind.SYSTEM_SECRET)
        .sortBy(_._2.id.get.id)
        .groupBy(_._2.kind).flatMap {
          case (kind, libs) =>
            val (slug, name, visibility) = if (kind == LibraryKind.SYSTEM_MAIN) ("main", Library.SYSTEM_MAIN_DISPLAY_NAME, LibraryVisibility.DISCOVERABLE) else ("secret", Library.SYSTEM_SECRET_DISPLAY_NAME, LibraryVisibility.SECRET)

            if (user.state == UserStates.ACTIVE) {
              val activeLib = libs.head._2.copy(state = LibraryStates.ACTIVE, slug = LibrarySlug(slug), name = name, visibility = visibility, memberCount = 1)
              val membership = libMem.find(m => m.libraryId == activeLib.id.get && m.access == LibraryAccess.OWNER)
              if (membership.isEmpty) airbrake.notify(s"user $userId - non-existing ownership of library kind $kind (id: ${activeLib.id.get})")
              val activeMembership = membership.getOrElse(LibraryMembership(libraryId = activeLib.id.get, userId = userId, access = LibraryAccess.OWNER)).copy(state = LibraryMembershipStates.ACTIVE)
              val active = (activeMembership, activeLib)
              if (libs.tail.nonEmpty) airbrake.notify(s"user $userId - duplicate active ownership of library kind $kind (ids: ${libs.tail.map(_._2.id.get)})")
              val otherLibs = libs.tail.map {
                case (a, l) =>
                  val inactMem = libMem.find(_.libraryId == l.id.get)
                    .getOrElse(LibraryMembership(libraryId = activeLib.id.get, userId = userId, access = LibraryAccess.OWNER))
                    .copy(state = LibraryMembershipStates.INACTIVE)
                  (inactMem, l.copy(state = LibraryStates.INACTIVE))
              }
              active +: otherLibs
            } else {
              // do not reactivate libraries / memberships for nonactive users
              libs
            }
        }.toList // force eval

      // save changes for active users only
      if (sysLibs.nonEmpty && user.state == UserStates.ACTIVE) {
        sysLibs.map {
          case (mem, lib) =>
            libraryRepo.save(lib)
            libraryMembershipRepo.save(mem)
        }
      }

      // If user is missing a system lib, create it
      val mainOpt = if (!sysLibs.exists(_._2.kind == LibraryKind.SYSTEM_MAIN)) {
        val mainLib = libraryRepo.save(Library(name = Library.SYSTEM_MAIN_DISPLAY_NAME, ownerId = userId, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("main"), kind = LibraryKind.SYSTEM_MAIN, memberCount = 1, keepCount = 0))
        libraryMembershipRepo.save(LibraryMembership(libraryId = mainLib.id.get, userId = userId, access = LibraryAccess.OWNER))
        if (!generateNew) {
          airbrake.notify(s"$userId missing main library")
        }
        searchClient.updateLibraryIndex()
        Some(mainLib)
      } else None

      val secretOpt = if (!sysLibs.exists(_._2.kind == LibraryKind.SYSTEM_SECRET)) {
        val secretLib = libraryRepo.save(Library(name = Library.SYSTEM_SECRET_DISPLAY_NAME, ownerId = userId, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("secret"), kind = LibraryKind.SYSTEM_SECRET, memberCount = 1, keepCount = 0))
        libraryMembershipRepo.save(LibraryMembership(libraryId = secretLib.id.get, userId = userId, access = LibraryAccess.OWNER))
        if (!generateNew) {
          airbrake.notify(s"$userId missing secret library")
        }
        searchClient.updateLibraryIndex()
        Some(secretLib)
      } else None

      val mainLib = sysLibs.find(_._2.kind == LibraryKind.SYSTEM_MAIN).map(_._2).orElse(mainOpt).get
      val secretLib = sysLibs.find(_._2.kind == LibraryKind.SYSTEM_SECRET).map(_._2).orElse(secretOpt).get
      (mainLib, secretLib)
    }
  }
  def copyKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag)(implicit context: HeimdalContext): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])] = {
    val keeps = db.readOnlyMaster { implicit s =>
      val keepIds = tagCommander.getKeepsByTagAndUser(tagName, userId)
      keepRepo.getActiveByIds(keepIds.toSet).values.toSeq
    }
    Right(copyKeeps(userId, libraryId, keeps.toSet, withSource = Some(KeepSource.TagImport)))
  }

  def moveKeepsFromCollectionToLibrary(userId: Id[User], libraryId: Id[Library], tagName: Hashtag)(implicit context: HeimdalContext): Either[LibraryFail, (Seq[Keep], Seq[(Keep, LibraryError)])] = {
    val keeps = db.readOnlyMaster { implicit s =>
      val keepIds = tagCommander.getKeepsByTagAndUser(tagName, userId)
      keepRepo.getActiveByIds(keepIds.toSet).values.toSeq
    }
    Right(moveKeeps(userId, libraryId, keeps))
  }

  def moveAllKeepsFromLibrary(userId: Id[User], fromLibraryId: Id[Library], toLibraryId: Id[Library])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)]) = {
    val keeps = db.readOnlyReplica { implicit session =>
      val keepIds = ktlRepo.getAllByLibraryId(fromLibraryId).map(_.keepId).toSet
      keepRepo.getActiveByIds(keepIds).values.toSeq
    }
    moveKeeps(userId, toLibraryId, keeps)
  }

  def moveKeeps(userId: Id[User], toLibraryId: Id[Library], keeps: Seq[Keep])(implicit context: HeimdalContext): (Seq[Keep], Seq[(Keep, LibraryError)]) = {
    db.readWrite { implicit s =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(toLibraryId, userId) match {
        case Some(membership) if membership.canWrite =>
          val toLibrary = libraryRepo.get(toLibraryId)
          val validSourceLibraryIds = keeps.flatMap(_.recipients.libraries).toSet.filter { fromLibraryId =>
            libraryMembershipRepo.getWithLibraryIdAndUserId(fromLibraryId, userId).exists(_.canWrite)
          }
          val failures = collection.mutable.ListBuffer[(Keep, LibraryError)]()
          val successes = collection.mutable.ListBuffer[Keep]()

          keeps.foreach {
            case keep if keep.recipients.libraries.exists(validSourceLibraryIds.contains) => keepMutator.moveKeep(keep, toLibrary, userId) match {
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
          val validSourceLibraryIds = {
            val allKeepLibraries = sortedKeeps.flatMap(_.recipients.libraries).toSet
            libraryMembershipRepo.getWithLibraryIdsAndUserId(allKeepLibraries, userId).keySet
          }

          val attrByKeep = keepSourceRepo.getRawByKeepIds(keeps.map(_.id.get))

          val (failures, successes) = sortedKeeps.map {
            case keep if keep.recipients.libraries.exists(validSourceLibraryIds.contains) =>
              keepMutator.copyKeep(keep, toLibrary, userId, withSource, attrByKeep.get(keep.id.get))(s) match {
                case Right(copied) => Right(copied)
                case Left(error) => Left(keep -> error)
              }
            case forbiddenKeep => Left(forbiddenKeep -> LibraryError.SourcePermissionDenied)
          }.partitionEithers

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
    ktlRepo.getCountsByLibraryIds(libraryIds).foreach {
      case (libraryId, keepCount) =>
        val library = libraryRepo.get(libraryId)
        if (library.keepCount != keepCount) {
          libraryRepo.save(library.copy(keepCount = keepCount))
        }
    }
  }

  def trackLibraryView(viewerId: Option[Id[User]], library: Library)(implicit context: HeimdalContext): Unit = {
    libraryAnalytics.viewedLibrary(viewerId, library, context)
  }

  def updateLastEmailSent(userId: Id[User], keeps: Seq[Keep]): Unit = {
    // persist when we last sent an email for each library membership
    db.readWrite { implicit rw =>
      val libIds: Set[Id[Library]] = keeps.flatMap(_.recipients.libraries).toSet
      libIds.foreach { libId =>
        libraryMembershipRepo.getWithLibraryIdAndUserId(libId, userId).foreach { libMembership =>
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

  def getUpdatesToLibrary(libraryId: Id[Library], since: DateTime): LibraryUpdates = {
    // Stop broken site, fix incoming
    // Really? Still waiting.
    LibraryUpdates(since, 0)
  }
}

protected object LibraryCommanderImpl {
  val slugPrefixRegex = """^(.*)-\d+$""".r
}

