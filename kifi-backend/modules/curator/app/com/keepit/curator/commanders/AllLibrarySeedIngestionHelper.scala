package com.keepit.curator.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ SequenceNumber, State }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.curator.model.{ CuratorLibraryInfo, CuratorLibraryInfoRepo }
import com.keepit.model.{ DetailedLibraryView, Library, Name, SystemValueRepo }
import com.keepit.shoebox.ShoeboxServiceClient

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@Singleton
class AllLibrarySeedIngestionHelper @Inject() (
    systemValueRepo: SystemValueRepo,
    libraryInfoRepo: CuratorLibraryInfoRepo,
    db: Database,
    airbrake: AirbrakeNotifier,
    shoebox: ShoeboxServiceClient) extends GlobalSeedIngestionHelper with Logging {

  private val SEQ_NUM_NAME: Name[SequenceNumber[Library]] = Name("all_libraries_seq_num")

  private def processNewLibrary(library: DetailedLibraryView)(implicit session: RWSession): Unit = {
    libraryInfoRepo.save(CuratorLibraryInfo(
      libraryId = library.id.get,
      ownerId = library.ownerId,
      memberCount = library.memberCount,
      keepCount = library.keepCount,
      visibility = library.visibility,
      lastKept = library.lastKept,
      lastFollowed = library.lastFollowed,
      kind = library.kind,
      libraryLastUpdated = library.updatedAt,
      state = State[CuratorLibraryInfo](library.state.value)
    ))
  }

  private def processUpdatedLibrary(library: DetailedLibraryView, libraryInfo: CuratorLibraryInfo)(implicit session: RWSession): Unit = {
    libraryInfoRepo.save(libraryInfo.copy(
      ownerId = library.ownerId,
      memberCount = library.memberCount,
      keepCount = library.keepCount,
      visibility = library.visibility,
      lastKept = library.lastKept,
      lastFollowed = library.lastFollowed,
      kind = library.kind,
      libraryLastUpdated = library.updatedAt,
      state = State[CuratorLibraryInfo](library.state.value)
    ))
  }

  private def processLibrary(library: DetailedLibraryView)(implicit session: RWSession): Unit = {
    libraryInfoRepo.getByLibraryId(library.id.get).map { libraryInfo =>
      processUpdatedLibrary(library, libraryInfo)
    } getOrElse {
      processNewLibrary(library)
    }
  }

  def apply(maxItems: Int): Future[Boolean] = {
    val lastSeqNumFut: Future[SequenceNumber[Library]] = db.readOnlyMasterAsync { implicit session =>
      systemValueRepo.getSequenceNumber(SEQ_NUM_NAME) getOrElse { SequenceNumber[Library](0) }
    }

    lastSeqNumFut.flatMap { lastSeqNum =>
      shoebox.getDetailedLibrariesChanged(lastSeqNum, maxItems).flatMap { libraries =>
        db.readWriteAsync { implicit session =>
          libraries.foreach(processLibrary)
          if (libraries.length > 0) systemValueRepo.setSequenceNumber(SEQ_NUM_NAME, libraries.map(_.seq).max)
          libraries.length >= maxItems
        }
      }
    }
  }

}
