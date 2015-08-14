package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model._

@ImplementedBy(classOf[KeepToLibraryCommanderImpl])
trait KeepToLibraryCommander {
  def internKeepInLibrary(ar: KeepToLibraryInternRequest)(implicit session: RWSession): Either[KeepToLibraryFail, KeepToLibraryInternResponse]
  def removeKeepFromLibrary(dr: KeepToLibraryRemoveRequest)(implicit session: RWSession): Either[KeepToLibraryFail, KeepToLibraryRemoveResponse]
  def removeKeepFromAllLibraries(keep: Keep)(implicit session: RWSession): Unit

  // Fun helper methods
  def isKeepInLibrary(keepId: Id[Keep], libraryId: Id[Library])(implicit session: RSession): Boolean
  def getKeeps(ktls: Seq[KeepToLibrary])(implicit session: RSession): Seq[Keep]
  def changeVisibility(ktl: KeepToLibrary, newVisibility: LibraryVisibility)(implicit session: RWSession): KeepToLibrary
  def changeOwner(ktl: KeepToLibrary, newOwnerId: Id[User])(implicit session: RWSession): KeepToLibrary
  def changeUriIdForKeep(keep: Keep, newUriId: Id[NormalizedURI])(implicit session: RWSession): Unit
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

  private def getValidationError(request: KeepToLibraryRequest)(implicit session: RSession): Option[KeepToLibraryFail] = {
    def userCanWrite = libraryMembershipRepo.getWithLibraryIdAndUserId(request.libraryId, request.requesterId).exists(_.canWrite)
    def keepIsActive = ktlRepo.getByKeepIdAndLibraryId(request.keepId, request.libraryId).exists(ktl => ktl.isActive)

    request match {
      case _: KeepToLibraryInternRequest =>
        if (!userCanWrite) Some(KeepToLibraryFail.INSUFFICIENT_PERMISSIONS)
        else None

      case _: KeepToLibraryRemoveRequest =>
        if (!userCanWrite) Some(KeepToLibraryFail.INSUFFICIENT_PERMISSIONS)
        else if (!keepIsActive) Some(KeepToLibraryFail.NOT_IN_LIBRARY)
        else None
    }
  }

  def internKeepInLibrary(request: KeepToLibraryInternRequest)(implicit session: RWSession): Either[KeepToLibraryFail, KeepToLibraryInternResponse] = {
    getValidationError(request).map(Left(_)).getOrElse {
      val ktl = ktlRepo.getByKeepIdAndLibraryId(request.keepId, request.libraryId, excludeStates = Set.empty) match {
        case Some(existingKtl) if existingKtl.isActive => existingKtl
        case existingKtlOpt =>
          val newKtlTemplate = KeepToLibrary(
            keepId = request.keepId,
            libraryId = request.libraryId,
            addedBy = request.requesterId,
            uriId = request.keep.uriId,
            isPrimary = request.keep.isPrimary,
            visibility = request.library.visibility,
            organizationId = request.library.organizationId
          )
          ktlRepo.save(newKtlTemplate.copy(id = existingKtlOpt.flatMap(_.id)))
      }
      Right(KeepToLibraryInternResponse(ktl))
    }
  }

  def removeKeepFromLibrary(request: KeepToLibraryRemoveRequest)(implicit session: RWSession): Either[KeepToLibraryFail, KeepToLibraryRemoveResponse] = {
    getValidationError(request).map(Left(_)).getOrElse {
      val ktl = ktlRepo.getByKeepIdAndLibraryId(request.keepId, request.libraryId).get // Guaranteed to return an active link
      ktlRepo.deactivate(ktl)
      Right(KeepToLibraryRemoveResponse())
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

  def changeVisibility(ktl: KeepToLibrary, newVisibility: LibraryVisibility)(implicit session: RWSession): KeepToLibrary = {
    ktlRepo.save(ktl.withVisibility(newVisibility))
  }
  def changeOwner(ktl: KeepToLibrary, newOwnerId: Id[User])(implicit session: RWSession): KeepToLibrary = {
    ktlRepo.save(ktl.withAddedBy(newOwnerId))
  }
  def changeUriIdForKeep(keep: Keep, newUriId: Id[NormalizedURI])(implicit session: RWSession): Unit = {
    require(keep.uriId == newUriId, "URI and Keep don't match.")
    ktlRepo.getAllByKeepId(keep.id.get).foreach { ktl =>
      ktlRepo.save(ktl.withUriId(newUriId))
    }
  }
}
