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
  def addKeepToLibrary(ar: KeepToLibraryAddRequest)(implicit session: RWSession): Either[KeepToLibraryFail, KeepToLibraryAddResponse]
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

  def getValidationError(r: KeepToLibraryRequest)(implicit session: RSession): Option[KeepToLibraryFail] = {
    def userCanWrite = libraryMembershipRepo.getWithLibraryIdAndUserId(libId, requesterId).exists(_.canWrite)
    def keepIsActivelyLinked = keepToLibraryRepo.getByKeepIdAndLibraryId(keepId, libId).exists(ktl => ktl.isActive)

    r match {
      case KeepToLibraryAddRequest(keepId, libId, requesterId) =>
        if (!userCanWrite) Some(KeepToLibraryFail.INSUFFICIENT_PERMISSIONS)
        else if (keepIsActivelyLinked) Some(KeepToLibraryFail.ALREADY_IN_LIBRARY)
        else None

      case KeepToLibraryRemoveRequest(keepId, libId, requesterId) =>
        if (!userCanWrite) Some(KeepToLibraryFail.INSUFFICIENT_PERMISSIONS)
        else if (!keepIsActivelyLinked) Some(KeepToLibraryFail.NOT_IN_LIBRARY)
        else None
    }
  }

  def addKeepToLibrary(ar: KeepToLibraryAddRequest)(implicit session: RWSession): Either[KeepToLibraryFail, KeepToLibraryAddResponse] = {
    getValidationError(ar).map(Left(_)).getOrElse {
      keepToLibraryRepo.getByKeepIdAndLibraryId(ar.keepId, ar.libraryId, excludeStates = Set.empty) match {
        case Some(ktl) =>
          if (ktl.isActive) {
            airbrake.notify(s"User ${ar.requesterId} managed to add a keep ${ar.keepId} a library ${ar.libraryId} where it already exists")
          }
          val reactivatedLink = keepToLibraryRepo.activate(ktl.copy(keeperId = ar.requesterId))
          Right(KeepToLibraryAddResponse(reactivatedLink))
        case None =>
          val newKtl = keepToLibraryRepo.save(KeepToLibrary(keepId = ar.keepId, libraryId = ar.libraryId, keeperId = ar.requesterId))
          Right(KeepToLibraryAddResponse(newKtl))
      }
    }
  }

  def removeKeepFromLibrary(dr: KeepToLibraryRemoveRequest)(implicit session: RWSession): Either[KeepToLibraryFail, KeepToLibraryRemoveResponse] = {
    getValidationError(dr).map(Left(_)).getOrElse {
      val ktl = keepToLibraryRepo.getByKeepIdAndLibraryId(dr.keepId, dr.libraryId).get // Guaranteed to return an active link
      keepToLibraryRepo.deactivate(ktl)
      Right(KeepToLibraryRemoveResponse())
    }
  }
}
