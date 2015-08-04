package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model._

@ImplementedBy(classOf[KeepToLibraryCommanderImpl])
trait KeepToLibraryCommander {
  def attach(ar: KeepToLibraryAttachRequest)(implicit session: RWSession): Either[KeepToLibraryFail, KeepToLibraryAttachResponse]
  def detach(dr: KeepToLibraryDetachRequest)(implicit session: RWSession): Either[KeepToLibraryFail, KeepToLibraryDetachResponse]
}

@Singleton
class KeepToLibraryCommanderImpl @Inject() (
  db: Database,
  keepRepo: KeepRepo,
  libraryRepo: LibraryRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  keepToLibraryRepo: KeepToLibraryRepo)
    extends KeepToLibraryCommander with Logging {

  def getValidationError(r: KeepToLibraryRequest)(implicit session: RSession): Option[KeepToLibraryFail] = {
    r match {
      case KeepToLibraryAttachRequest(keepId, libId, requesterId) =>
        val userCanWrite = libraryMembershipRepo.getWithLibraryIdAndUserId(libId, requesterId).exists(_.canWrite)
        val keepIsActivelyLinked = keepToLibraryRepo.getByKeepIdAndLibraryId(keepId, libId).exists(_.isActive)
        if (!userCanWrite) Some(KeepToLibraryFail.INSUFFICIENT_PERMISSIONS)
        else if (keepIsActivelyLinked) Some(KeepToLibraryFail.ALREADY_LINKED)
        else None

      case KeepToLibraryDetachRequest(keepId, libId, requesterId) =>
        val userCanRemove = libraryMembershipRepo.getWithLibraryIdAndUserId(libId, requesterId).exists(_.canWrite)
        val keepIsActivelyLinked = keepToLibraryRepo.getByKeepIdAndLibraryId(keepId, libId).exists(_.isActive)
        if (!userCanRemove) Some(KeepToLibraryFail.INSUFFICIENT_PERMISSIONS)
        else if (!keepIsActivelyLinked) Some(KeepToLibraryFail.NOT_LINKED)
        else None
    }
  }

  def attach(ar: KeepToLibraryAttachRequest)(implicit session: RWSession): Either[KeepToLibraryFail, KeepToLibraryAttachResponse] = {
    getValidationError(ar) match {
      case Some(fail) => Left(fail)
      case None =>
        keepToLibraryRepo.getByKeepIdAndLibraryId(ar.keepId, ar.libraryId, excludeStates = Set.empty) match {
          case Some(link) =>
            assert(link.isInactive) // existing link guaranteed to be inactive
            val reactivatedLink = keepToLibraryRepo.activate(link.copy(keeperId = ar.requesterId))
            Right(KeepToLibraryAttachResponse(reactivatedLink))
          case None =>
            val newLink = keepToLibraryRepo.save(KeepToLibrary(keepId = ar.keepId, libraryId = ar.libraryId, keeperId = ar.requesterId))
            Right(KeepToLibraryAttachResponse(newLink))
        }
    }
  }

  def detach(dr: KeepToLibraryDetachRequest)(implicit session: RWSession): Either[KeepToLibraryFail, KeepToLibraryDetachResponse] = {
    getValidationError(dr) match {
      case Some(fail) => Left(fail)
      case None =>
        val link = keepToLibraryRepo.getByKeepIdAndLibraryId(dr.keepId, dr.libraryId).get // Guaranteed to return an active link
        keepToLibraryRepo.deactivate(link)
        Right(KeepToLibraryDetachResponse())
    }
  }
}
