package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import play.api.http.Status._

import scala.concurrent.{ ExecutionContext, Future }

class LibraryModifierCommander @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryAliasRepo: LibraryAliasRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    librarySubscriptionCommander: LibrarySubscriptionCommander,
    keepRepo: KeepRepo,
    airbrake: AirbrakeNotifier,
    searchClient: SearchServiceClient,
    libraryAnalytics: LibraryAnalytics,
    ktlRepo: KeepToLibraryRepo,
    ktlCommander: KeepToLibraryCommander,
    implicit val defaultContext: ExecutionContext,
    organizationMembershipCommander: OrganizationMembershipCommander) {

  def canModifyLibrary(libraryId: Id[Library], userId: Id[User]): Boolean = {
    db.readOnlyReplica { implicit s => libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId) } exists { membership => //not cached!
      membership.canWrite
    }
  }

  def canMoveTo(userId: Id[User], libId: Id[Library], to: LibrarySpace): Boolean = {
    db.readOnlyMaster { implicit session =>
      val library = libraryRepo.get(libId)
      val from: LibrarySpace = library.space
      val userOwnsLibrary = library.ownerId == userId
      val canMoveFromSpace = from match {
        case OrganizationSpace(fromOrg) =>
          library.ownerId == userId
        // TODO(ryan): when the frontend has UI for this, add it in
        // organizationMembershipCommander.getPermissions(fromOrg, Some(userId)).contains(OrganizationPermission.REMOVE_LIBRARIES)
        case UserSpace(fromUser) => fromUser == userId // Can move libraries from Personal space to Organization Space.
      }
      val canMoveToSpace = to match {
        case OrganizationSpace(toOrg) => organizationMembershipCommander.getPermissions(toOrg, Some(userId)).contains(OrganizationPermission.ADD_LIBRARIES)
        case UserSpace(toUser) => toUser == userId // Can move from Organization Space to Personal space.
      }
      userOwnsLibrary && canMoveFromSpace && canMoveToSpace
    }
  }

  def modifyLibrary(libraryId: Id[Library], userId: Id[User], modifyReq: LibraryModifyRequest)(implicit context: HeimdalContext): Either[LibraryFail, LibraryModifyResponse] = {
    val (targetLib, targetMembershipOpt) = db.readOnlyMaster { implicit s =>
      val lib = libraryRepo.get(libraryId)
      val mem = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
      (lib, mem)
    }

    if (!targetMembershipOpt.exists(_.canWrite)) {
      Left(LibraryFail(FORBIDDEN, "permission_denied"))
    } else {
      def validSpace(newSpaceOpt: Option[LibrarySpace]): Either[LibraryFail, LibrarySpace] = {
        newSpaceOpt match {
          case None => Right(targetLib.space)
          case Some(newSpace) =>
            if (canMoveTo(userId = userId, libId = libraryId, to = newSpace)) {
              Right(newSpace)
            } else {
              Left(LibraryFail(BAD_REQUEST, "invalid_space"))
            }
        }
      }

      def validName(newNameOpt: Option[String], newSpace: LibrarySpace): Either[LibraryFail, String] = {
        newNameOpt match {
          case None => Right(targetLib.name)
          case Some(name) =>
            if (!Library.isValidName(name)) {
              Left(LibraryFail(BAD_REQUEST, "invalid_name"))
            } else {
              db.readOnlyMaster { implicit s =>
                libraryRepo.getBySpaceAndName(newSpace, name)
              } match {
                case Some(other) if other.id.get != libraryId => Left(LibraryFail(BAD_REQUEST, "library_name_exists"))
                case _ => Right(name)
              }
            }
        }
      }

      def validSlug(newSlugOpt: Option[String], newSpace: LibrarySpace): Either[LibraryFail, LibrarySlug] = {
        newSlugOpt match {
          case None => Right(targetLib.slug)
          case Some(slugStr) =>
            if (!LibrarySlug.isValidSlug(slugStr)) {
              Left(LibraryFail(BAD_REQUEST, "invalid_slug"))
            } else if (LibrarySlug.isReservedSlug(slugStr)) {
              Left(LibraryFail(BAD_REQUEST, "reserved_slug"))
            } else {
              val slug = LibrarySlug(slugStr)
              db.readOnlyMaster { implicit s =>
                libraryRepo.getBySpaceAndSlug(newSpace, slug)
              } match {
                case Some(other) if other.id.get != libraryId => Left(LibraryFail(BAD_REQUEST, "library_slug_exists"))
                case _ => Right(slug)
              }
            }
        }
      }

      def validVisibility(newVisibilityOpt: Option[LibraryVisibility], newSpace: LibrarySpace): Either[LibraryFail, LibraryVisibility] = {
        newVisibilityOpt match {
          case None => Right(targetLib.visibility)
          case Some(newVisibility) =>
            newSpace match {
              case _: UserSpace if newVisibility == LibraryVisibility.ORGANIZATION => Left(LibraryFail(BAD_REQUEST, "invalid_visibility"))
              case _ => Right(newVisibility)
            }

        }
      }

      val targetMembership = targetMembershipOpt.get
      val currentSpace = targetLib.space
      val newSpaceOpt = modifyReq.space
      val newSubKeysOpt = modifyReq.subscriptions

      val result = for {
        newSpace <- validSpace(newSpaceOpt).right
        newName <- validName(modifyReq.name, newSpace).right
        newSlug <- validSlug(modifyReq.slug, newSpace).right
        newVisibility <- validVisibility(modifyReq.visibility, newSpace).right
      } yield {
        val newDescription = modifyReq.description.orElse(targetLib.description)
        val newColor = modifyReq.color.orElse(targetLib.color)
        val newListed = modifyReq.listed.getOrElse(targetMembership.listed)
        val newInviteToCollab = modifyReq.whoCanInvite.orElse(targetLib.whoCanInvite)

        // New library subscriptions
        newSubKeysOpt match {
          case Some(newSubKeys) => db.readWrite { implicit s =>
            librarySubscriptionCommander.updateSubsByLibIdAndKey(targetLib.id.get, newSubKeys)
          }
          case None =>
        }

        val lib = db.readWrite { implicit s =>
          if (newSpace != currentSpace || newSlug != targetLib.slug) {
            libraryAliasRepo.reclaim(newSpace, newSlug) // There is now a real library there; dump the alias
            libraryAliasRepo.alias(currentSpace, targetLib.slug, targetLib.id.get) // Make a new alias for where targetLib used to live
          }
          if (targetMembership.listed != newListed) {
            libraryMembershipRepo.save(targetMembership.copy(listed = newListed))
          }

          val newOrgId = newSpace match {
            case OrganizationSpace(orgId) => Some(orgId)
            case UserSpace(_) => None
          }

          libraryRepo.save(targetLib.copy(name = newName, slug = newSlug, visibility = newVisibility, description = newDescription, color = newColor, whoCanInvite = newInviteToCollab, state = LibraryStates.ACTIVE, organizationId = newOrgId))
        }

        // Update visibility of keeps
        // TODO(ryan): Change this method so that it operates exclusively on KTLs. Keeps should not have visibility anymore
        def updateKeepVisibility(changedVisibility: LibraryVisibility, iter: Int): Future[Unit] = Future {
          val (keeps, lib, curViz) = db.readOnlyMaster { implicit s =>
            val lib = libraryRepo.get(targetLib.id.get)
            val viz = lib.visibility // It may have changed, re-check
            val keeps = keepRepo.getByLibraryIdAndExcludingVisibility(libraryId, Some(viz), 1000)
            (keeps, lib, viz)
          }
          if (keeps.nonEmpty && curViz == changedVisibility) {
            db.readWriteBatch(keeps, attempts = 5) { (s, k) =>
              implicit val session: RWSession = s
              syncWithLibrary(k, lib)
            }
            if (iter < 200) { // to prevent infinite loops if there's an issue updating keeps.
              updateKeepVisibility(changedVisibility, iter + 1)
            } else {
              val msg = s"[updateKeepVisibility] Problems updating visibility on $libraryId to $curViz, $iter"
              airbrake.notify(msg)
              Future.failed(new Exception(msg))
            }
          } else {
            Future.successful(())
          }
        }.flatMap(m => m)

        val keepChanges = updateKeepVisibility(newVisibility, 0)
        keepChanges.onComplete { _ => searchClient.updateKeepIndex() }

        val edits = Map(
          "title" -> (newName != targetLib.name),
          "slug" -> (newSlug != targetLib.slug),
          "description" -> (newDescription != targetLib.description),
          "color" -> (newColor != targetLib.color),
          "madePrivate" -> (newVisibility != targetLib.visibility && newVisibility == LibraryVisibility.SECRET),
          "listed" -> (newListed != targetMembership.listed),
          "inviteToCollab" -> (newInviteToCollab != targetLib.whoCanInvite),
          "space" -> (newSpace != targetLib.space)
        )
        (lib, edits, keepChanges)
      }

      Future {
        if (result.isRight) {
          val editedLibrary = result.right.get._1
          val edits = result.right.get._2
          libraryAnalytics.editLibrary(userId, editedLibrary, context, None, edits)
        }
        searchClient.updateLibraryIndex()
      }
      result match {
        case Right((lib, _, keepChanges)) => Right(LibraryModifyResponse(lib, keepChanges))
        case Left(error) => Left(error)
      }
    }
  }

  private def syncWithLibrary(keep: Keep, library: Library)(implicit session: RWSession): Keep = {
    require(keep.libraryId == library.id, "keep.libraryId does not match library id!")
    ktlRepo.getByKeepIdAndLibraryId(keep.id.get, library.id.get).foreach { ktl => ktlCommander.syncWithLibrary(ktl, library) }
    keepRepo.save(keep.withLibrary(library))
  }
}
