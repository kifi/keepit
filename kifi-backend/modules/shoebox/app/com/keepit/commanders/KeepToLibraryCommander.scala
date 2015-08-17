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
  def syncWithKeep(ktl: KeepToLibrary, keep: Keep)(implicit session: RWSession): KeepToLibrary
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

  override def internKeepInLibrary(keep: Keep, library: Library, addedBy: Id[User])(implicit session: RWSession): KeepToLibrary = {
    ktlRepo.getByKeepIdAndLibraryId(keep.id.get, library.id.get, excludeStates = Set.empty) match {
      case Some(existingKtl) if existingKtl.isActive => existingKtl
      case existingKtlOpt =>
        val newKtlTemplate = KeepToLibrary(
          keepId = keep.id.get,
          libraryId = library.id.get,
          addedBy = addedBy,
          uriId = keep.uriId,
          isPrimary = keep.isPrimary,
          visibility = library.visibility,
          organizationId = library.organizationId
        )
        ktlRepo.save(newKtlTemplate.copy(id = existingKtlOpt.flatMap(_.id)))
    }
  }

  override def removeKeepFromLibrary(keepId: Id[Keep], libraryId: Id[Library])(implicit session: RWSession): Try[Unit] = {
    ktlRepo.getByKeepIdAndLibraryId(keepId, libraryId) match {
      case None => Failure(KeepToLibraryFail.NOT_IN_LIBRARY)
      case Some(activeKtl) =>
        ktlRepo.deactivate(activeKtl)
        Success(())
    }
  }
  override def removeKeepFromAllLibraries(keep: Keep)(implicit session: RWSession): Unit = {
    ktlRepo.getAllByKeepId(keep.id.get).foreach(ktlRepo.deactivate)
  }

  override def isKeepInLibrary(keepId: Id[Keep], libraryId: Id[Library])(implicit session: RSession): Boolean = {
    ktlRepo.getByKeepIdAndLibraryId(keepId, libraryId).isDefined
  }
  override def getKeeps(ktls: Seq[KeepToLibrary])(implicit session: RSession): Seq[Keep] = {
    val keepsByIds = keepRepo.getByIds(ktls.map(_.keepId).toSet)
    ktls.map(ktl => keepsByIds(ktl.keepId))
  }

  override def changeOwner(ktl: KeepToLibrary, newOwnerId: Id[User])(implicit session: RWSession): KeepToLibrary = {
    ktlRepo.save(ktl.withAddedBy(newOwnerId))
  }

  override def syncKeep(keep: Keep)(implicit session: RWSession): Unit = {
    ktlRepo.getAllByKeepId(keep.id.get).foreach { ktl => syncWithKeep(ktl, keep) }
  }
  override def syncWithKeep(ktl: KeepToLibrary, keep: Keep)(implicit session: RWSession): KeepToLibrary = {
    ktlRepo.save(ktl.withUriId(keep.uriId).withPrimary(keep.isPrimary))
  }
  override def syncWithLibrary(ktl: KeepToLibrary, lib: Library)(implicit session: RWSession): KeepToLibrary = {
    ktlRepo.save(ktl.withVisibility(lib.visibility).withOrganizationId(lib.organizationId))
  }
}
