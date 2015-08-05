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
  def internKeepToLibrary(ar: KeepToLibraryInternRequest)(implicit session: RWSession): Either[KeepToLibraryFail, KeepToLibraryInternResponse]
  def removeKeepFromLibrary(dr: KeepToLibraryRemoveRequest)(implicit session: RWSession): Either[KeepToLibraryFail, KeepToLibraryRemoveResponse]
}

@Singleton
class KeepToLibraryCommanderImpl @Inject() (
  db: Database,
  keepRepo: KeepRepo,
  libraryRepo: LibraryRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  keepToLibraryRepo: KeepToLibraryRepo,
  airbrake: AirbrakeNotifier)
    extends KeepToLibraryCommander with Logging {

  private def getValidationError(request: KeepToLibraryRequest)(implicit session: RSession): Option[KeepToLibraryFail] = {
    def userCanWrite = libraryMembershipRepo.getWithLibraryIdAndUserId(request.libraryId, request.requesterId).exists(_.canWrite)
    def keepIsActive = keepToLibraryRepo.getByKeepIdAndLibraryId(request.keepId, request.libraryId).exists(ktl => ktl.isActive)

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

  def internKeepToLibrary(request: KeepToLibraryInternRequest)(implicit session: RWSession): Either[KeepToLibraryFail, KeepToLibraryInternResponse] = {
    getValidationError(request).map(Left(_)).getOrElse {
      val ktl = keepToLibraryRepo.getByKeepIdAndLibraryId(request.keepId, request.libraryId, excludeStates = Set.empty) match {
        case Some(existingKtl) if existingKtl.isActive => existingKtl
        case existingKtlOpt =>
          val newKtlTemplate = KeepToLibrary(keepId = request.keepId, libraryId = request.libraryId, addedBy = request.requesterId) // no id yet
          keepToLibraryRepo.save(newKtlTemplate.copy(id = existingKtlOpt.flatMap(_.id)))
      }
      Right(KeepToLibraryInternResponse(ktl))
    }
  }

  def removeKeepFromLibrary(request: KeepToLibraryRemoveRequest)(implicit session: RWSession): Either[KeepToLibraryFail, KeepToLibraryRemoveResponse] = {
    getValidationError(request).map(Left(_)).getOrElse {
      val ktl = keepToLibraryRepo.getByKeepIdAndLibraryId(request.keepId, request.libraryId).get // Guaranteed to return an active link
      keepToLibraryRepo.deactivate(ktl)
      Right(KeepToLibraryRemoveResponse())
    }
  }
}
