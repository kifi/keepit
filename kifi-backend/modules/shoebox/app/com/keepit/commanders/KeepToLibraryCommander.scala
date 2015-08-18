package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model._

import scala.util.{ Success, Failure, Try }

@ImplementedBy(classOf[KeepToLibraryCommanderImpl])
trait KeepToLibraryCommander {
  def internKeepInLibrary(keep: Keep, library: Library, addedBy: Id[User])(implicit session: RWSession): KeepToLibrary
  def removeKeepFromLibrary(keepId: Id[Keep], libraryId: Id[Library])(implicit session: RWSession): Try[Unit]
  def removeKeepFromAllLibraries(keep: Keep)(implicit session: RWSession): Unit

  // Fun helper methods
  def isKeepInLibrary(keepId: Id[Keep], libraryId: Id[Library])(implicit session: RSession): Boolean
  def getKeeps(ktls: Seq[KeepToLibrary])(implicit session: RSession): Seq[Keep]
  def changeOwner(ktl: KeepToLibrary, newOwnerId: Id[User])(implicit session: RWSession): KeepToLibrary

  def syncKeep(keep: Keep)(implicit session: RWSession): Unit
  // TODO(ryan): make this private and expose a public method `syncLibrary(lib): Future[Unit]`
  def syncWithLibrary(ktl: KeepToLibrary, lib: Library)(implicit session: RWSession): KeepToLibrary
}

@Singleton
class KeepToLibraryCommanderImpl @Inject() (
  db: Database,
  keepRepo: KeepRepo,
  libraryRepo: LibraryRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  ktlRepo: KeepToLibraryRepo,
  airbrake: AirbrakeNotifier)
    extends KeepToLibraryCommander with Logging {

  def internKeepInLibrary(keep: Keep, library: Library, addedBy: Id[User])(implicit session: RWSession): KeepToLibrary = {
    ktlRepo.getByKeepIdAndLibraryId(keep.id.get, library.id.get, excludeStates = Set.empty) match {
      case Some(existingKtl) if existingKtl.isActive => existingKtl
      case existingKtlOpt =>
        val newKtlTemplate = KeepToLibrary(
          keepId = keep.id.get,
          libraryId = library.id.get,
          addedBy = addedBy,
          addedAt = keep.keptAt, // TODO(ryan): take this out once we're ready to have keeps in multiple libraries
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
  def getKeeps(ktls: Seq[KeepToLibrary])(implicit session: RSession): Seq[Keep] = {
    val keepsByIds = keepRepo.getByIds(ktls.map(_.keepId).toSet)
    ktls.map(ktl => keepsByIds(ktl.keepId))
  }

  def changeOwner(ktl: KeepToLibrary, newOwnerId: Id[User])(implicit session: RWSession): KeepToLibrary = {
    ktlRepo.save(ktl.withAddedBy(newOwnerId))
  }

  def syncKeep(keep: Keep)(implicit session: RWSession): Unit = {
    ktlRepo.getAllByKeepId(keep.id.get).foreach { ktl => syncWithKeep(ktl, keep) }
  }
  private def syncWithKeep(ktl: KeepToLibrary, keep: Keep)(implicit session: RWSession): KeepToLibrary = {
    require(ktl.keepId == keep.id.get, "keep.id does not match ktl.keepId")
    val obstacleKtl = ktlRepo.getPrimaryByUriAndLibrary(keep.uriId, ktl.libraryId)
    if (obstacleKtl.exists(_.id.get != ktl.id.get) && keep.isPrimary) {
      log.error(s"[KTL-ERROR] About to sync $ktl with $keep, but ${obstacleKtl.get} is in the way")
    }
    ktlRepo.save(ktl.withUriId(keep.uriId).withPrimary(keep.isPrimary))
  }
  def syncWithLibrary(ktl: KeepToLibrary, library: Library)(implicit session: RWSession): KeepToLibrary = {
    require(ktl.libraryId == library.id.get, "library.id does not match ktl.libraryId")
    ktlRepo.save(ktl.withVisibility(library.visibility).withOrganizationId(library.organizationId))
  }
}
