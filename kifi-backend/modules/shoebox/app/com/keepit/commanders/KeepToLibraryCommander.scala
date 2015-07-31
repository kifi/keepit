package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model._

@ImplementedBy(classOf[KeepToLibraryCommanderImpl])
trait KeepToLibraryCommander {
  def addKeepToLibrary(keep: Keep, libraryId: Id[Library])(implicit session: RWSession): KeepToLibrary
  def removeKeepFromLibrary(keepId: Id[Keep], libraryId: Id[Library])(implicit session: RWSession): Unit
}

@Singleton
class KeepToLibraryCommanderImpl @Inject() (
  db: Database,
  keepRepo: KeepRepo,
  keepToLibraryRepo: KeepToLibraryRepo)
    extends KeepToLibraryCommander with Logging {

  def addKeepToLibrary(keep: Keep, libraryId: Id[Library])(implicit session: RWSession): KeepToLibrary = {
    db.readWrite { implicit session =>
      keepToLibraryRepo.getByKeepIdAndLibraryId(keep.id.get, libraryId, excludeStateOpt = None) match {
        case Some(keepToLib) if keepToLib.isActive =>
          keepToLib
        case Some(keepToLib) if keepToLib.isInactive =>
          keepToLibraryRepo.activate(keepToLib)
        case None =>
          keepToLibraryRepo.save(KeepToLibrary(keepId = keep.id.get, libraryId = libraryId, keeperId = keep.userId))
      }
    }
  }

  def removeKeepFromLibrary(keepId: Id[Keep], libraryId: Id[Library])(implicit session: RWSession): Unit = {
    keepToLibraryRepo.getByKeepIdAndLibraryId(keepId, libraryId, excludeStateOpt = None) match {
      case Some(keepToLib) if keepToLib.isActive =>
        keepToLibraryRepo.deactivate(keepToLib)
      case _ => ()
    }
  }
}
