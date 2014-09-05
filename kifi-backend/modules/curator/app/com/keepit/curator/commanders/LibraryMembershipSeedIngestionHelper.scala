package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.{ State, SequenceNumber }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.curator.model.{ CuratorLibraryMembershipInfo, CuratorLibraryMembershipInfoRepo }
import com.keepit.model.{ LibraryMembership, LibraryKind, LibraryAndMemberships, Library, Name, SystemValueRepo }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future

class LibraryMembershipSeedIngestionHelper @Inject() (
    systemValueRepo: SystemValueRepo,
    libInfoRepo: CuratorLibraryMembershipInfoRepo,
    db: Database,
    shoebox: ShoeboxServiceClient) extends GlobalSeedIngestionHelper with Logging {

  private val SEQ_NUM_NAME: Name[SequenceNumber[LibraryMembership]] = Name("all_library_membership_seq_num")

  private def processLibraryMemberships(libMembership: LibraryMembership)(implicit session: RWSession): Unit = {
    libInfoRepo.getByUserAndLibraryId(libMembership.userId, libMembership.libraryId).map { libMembershipInfo =>
      libInfoRepo.save(libMembershipInfo.copy(
        access = libMembership.access,
        state = State[CuratorLibraryMembershipInfo](libMembership.state.value)
      ))
    } getOrElse {
      libInfoRepo.save(CuratorLibraryMembershipInfo(
        userId = libMembership.userId,
        libraryId = libMembership.libraryId,
        access = libMembership.access,
        state = State[CuratorLibraryMembershipInfo](libMembership.state.value)))
    }
  }

  def apply(maxItems: Int): Future[Boolean] = {
    val lastSeqNumFut: Future[SequenceNumber[LibraryMembership]] = db.readOnlyMasterAsync { implicit session =>
      systemValueRepo.getSequenceNumber(SEQ_NUM_NAME) getOrElse { SequenceNumber[LibraryMembership](0) }
    }

    lastSeqNumFut.flatMap { lastSeqNum =>
      shoebox.getLibraryMembershipChanged(lastSeqNum, maxItems).flatMap { libMemberships =>
        db.readWriteAsync { implicit session =>
          libMemberships.foreach(processLibraryMemberships)
          if (libMemberships.length > 0) systemValueRepo.setSequenceNumber(SEQ_NUM_NAME, libMemberships.map(_.seq).max)
          libMemberships.length >= maxItems
        }
      }
    }
  }
}
