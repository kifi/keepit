package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.{ State, SequenceNumber }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.curator.model.{ CuratorLibraryMembershipInfo, CuratorLibraryMembershipInfoRepo }
import com.keepit.model.{ LibraryKind, LibraryAndMemberships, Library, Name, SystemValueRepo }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future

class LibraryMembershipSeedIngestionHelper @Inject() (
    systemValueRepo: SystemValueRepo,
    libInfoRepo: CuratorLibraryMembershipInfoRepo,
    db: Database,
    shoebox: ShoeboxServiceClient) extends GlobalSeedIngestionHelper with Logging {
  //triggers ingestions of up to maxItem RawSeedItems. Returns true if there might be more items to be ingested, false otherwise

  private val SEQ_NUM_NAME: Name[SequenceNumber[Library]] = Name("all_library_seq_num")

  private def processLibraryMemberships(libMembership: LibraryAndMemberships)(implicit session: RWSession): Unit = {
    libMembership.memberships.foreach(membership =>
      libInfoRepo.save(CuratorLibraryMembershipInfo(
        userId = membership.userId,
        libraryId = membership.libraryId,
        kind = libMembership.library.kind,
        access = membership.access,
        state = State[CuratorLibraryMembershipInfo](membership.state.value)))
    )
  }

  def apply(maxItems: Int): Future[Boolean] = {
    val lastSeqNumFut: Future[SequenceNumber[Library]] = db.readOnlyMasterAsync { implicit session =>
      systemValueRepo.getSequenceNumber(SEQ_NUM_NAME) getOrElse { SequenceNumber[Library](0) }
    }

    lastSeqNumFut.flatMap { lastSeqNum =>
      shoebox.getLibrariesAndMembershipsChanged(lastSeqNum, maxItems).flatMap { libsAndMemberships =>
        db.readWriteAsync { implicit session =>
          libsAndMemberships.foreach { item =>
            if (item.library.kind != LibraryKind.SYSTEM_MAIN && item.library.kind != LibraryKind.SYSTEM_SECRET)
              processLibraryMemberships(item)
          }
          if (libsAndMemberships.length > 0) systemValueRepo.setSequenceNumber(SEQ_NUM_NAME, libsAndMemberships.map(_.library.seq).max)
          libsAndMemberships.length >= maxItems
        }
      }
    }
  }
}
