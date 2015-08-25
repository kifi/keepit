package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model._

import scala.util.control.NoStackTrace
import scala.util.{ Failure, Success, Try }

sealed abstract class KeepToLibraryFail(val message: String) extends Exception(message) with NoStackTrace
object KeepToLibraryFail {
  case object NOT_IN_LIBRARY extends KeepToLibraryFail("keep_not_in_library")

  def apply(str: String): KeepToLibraryFail = {
    str match {
      case NOT_IN_LIBRARY.message => NOT_IN_LIBRARY
    }
  }
}

@ImplementedBy(classOf[KeepToLibraryCommanderImpl])
trait KeepToLibraryCommander {
  def internKeepInLibrary(keep: Keep, library: Library, addedBy: Id[User])(implicit session: RWSession): KeepToLibrary
  def removeKeepFromLibrary(keepId: Id[Keep], libraryId: Id[Library])(implicit session: RWSession): Try[Unit]
  def removeKeepFromAllLibraries(keep: Keep)(implicit session: RWSession): Unit

  // Fun helper methods
  def isKeepInLibrary(keepId: Id[Keep], libraryId: Id[Library])(implicit session: RSession): Boolean
  def changeOwner(ktl: KeepToLibrary, newOwnerId: Id[User])(implicit session: RWSession): KeepToLibrary

  def syncKeep(keep: Keep)(implicit session: RWSession): Unit
  // TODO(ryan): make this private and expose a public method `syncLibrary(lib): Future[Unit]`
  def syncWithLibrary(ktl: KeepToLibrary, lib: Library)(implicit session: RWSession): KeepToLibrary
}

@Singleton
class KeepToLibraryCommanderImpl @Inject() (
  db: Database,
  clock: Clock,
  keepRepo: KeepRepo,
  libraryRepo: LibraryRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  ktlRepo: KeepToLibraryRepo,
  airbrake: AirbrakeNotifier)
    extends KeepToLibraryCommander with Logging {

  def internKeepInLibrary(keep: Keep, library: Library, addedBy: Id[User])(implicit session: RWSession): KeepToLibrary = {
    ktlRepo.getByKeepIdAndLibraryId(keep.id.get, library.id.get, excludeStateOpt = None) match {
      case Some(existingKtl) if existingKtl.isActive => existingKtl
      case existingKtlOpt =>
        val newKtlTemplate = KeepToLibrary(
          keepId = keep.id.get,
          libraryId = library.id.get,
          addedBy = addedBy,
          addedAt = clock.now,
          uriId = keep.uriId,
          isPrimary = keep.isPrimary,
          visibility = library.visibility,
          organizationId = library.organizationId
        )
        ktlRepo.save(newKtlTemplate.copy(id = existingKtlOpt.flatMap(_.id)))
    }
  }

  def removeKeepFromLibrary(keepId: Id[Keep], libraryId: Id[Library])(implicit session: RWSession): Try[Unit] = {
    ktlRepo.getByKeepIdAndLibraryId(keepId, libraryId) match {
      case None => Failure(KeepToLibraryFail.NOT_IN_LIBRARY)
      case Some(activeKtl) =>
        ktlRepo.deactivate(activeKtl)
        Success(())
    }
  }
  def removeKeepFromAllLibraries(keep: Keep)(implicit session: RWSession): Unit = {
    ktlRepo.getAllByKeepId(keep.id.get).foreach(ktlRepo.deactivate)
  }

  def isKeepInLibrary(keepId: Id[Keep], libraryId: Id[Library])(implicit session: RSession): Boolean = {
    ktlRepo.getByKeepIdAndLibraryId(keepId, libraryId).isDefined
  }

  def changeOwner(ktl: KeepToLibrary, newOwnerId: Id[User])(implicit session: RWSession): KeepToLibrary = {
    ktlRepo.save(ktl.withAddedBy(newOwnerId))
  }

  def syncKeep(keep: Keep)(implicit session: RWSession): Unit = {
    // Sync ALL of the keeps (including the dead ones)
    ktlRepo.getAllByKeepId(keep.id.get, excludeStateOpt = None).foreach { ktl => syncWithKeep(ktl, keep) }
  }
  private def syncWithKeep(ktl: KeepToLibrary, keep: Keep)(implicit session: RWSession): KeepToLibrary = {
    require(ktl.keepId == keep.id.get, "keep.id does not match ktl.keepId")
    ktlRepo.save(ktl.withUriId(keep.uriId).withPrimary(ktl.isActive && keep.isPrimary))
  }
  def syncWithLibrary(ktl: KeepToLibrary, library: Library)(implicit session: RWSession): KeepToLibrary = {
    require(ktl.libraryId == library.id.get, "library.id does not match ktl.libraryId")
    ktlRepo.save(ktl.withVisibility(library.visibility).withOrganizationId(library.organizationId))
  }
}
