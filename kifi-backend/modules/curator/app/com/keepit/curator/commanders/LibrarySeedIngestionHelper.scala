package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.{State, SequenceNumber}
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.curator.model.{CuratorLibraryInfo, CuratorLibraryInfoRepo, RawSeedItemRepo}
import com.keepit.model.{LibraryAndMemberships, Library, Name, SystemValueRepo}
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.Future

class LibrarySeedIngestionHelper @Inject() (
  systemValueRepo: SystemValueRepo,
  libInfoRepo: CuratorLibraryInfoRepo,
  rawSeedsRepo: RawSeedItemRepo,
  db: Database,
  airbrake: AirbrakeNotifier,
  shoebox: ShoeboxServiceClient
)  extends GlobalSeedIngestionHelper with Logging {
  //triggers ingestions of up to maxItem RawSeedItems. Returns true if there might be more items to be ingested, false otherwise

  private val SEQ_NUM_NAME: Name[SequenceNumber[Library]] = Name("all_library_seq_num")

  private def processUpdateLibraryMemberships(libMembership: LibraryAndMemberships, libInfo: CuratorLibraryInfo) = {

  }

  private def processNewLibraryMemberships(libMembership: LibraryAndMemberships)(implicit session: RWSession): Unit = {
    val userSet = libMembership.memberships.map(_.userId).toSet
    libInfoRepo.save(CuratorLibraryInfo(
      uriId = None,
      userSet = userSet,
      libraryId = libMembership.library.id.get,
      kind = libMembership.library.kind,
      state = State[CuratorLibraryInfo](libMembership.library.state.value)
    ))
  }


  private def processLibraryMemberships(libMembership: LibraryAndMemberships)(implicit session: RWSession): Unit = {
    //find out is possible library id null?
    libInfoRepo.getByLibraryId(libMembership.library.id.get).map { libInfo =>
      processUpdateLibraryMemberships(libMembership, libInfo)
    } getOrElse {
      processNewLibraryMemberships(libMembership)
    }
  }

  override def apply(maxItems: Int): Future[Boolean] = {
    val lastSeqNumFut: Future[SequenceNumber[Library]] = db.readOnlyMasterAsync { implicit session =>
      systemValueRepo.getSequenceNumber(SEQ_NUM_NAME) getOrElse { SequenceNumber[Library](0) }
    }

    lastSeqNumFut.flatMap { lastSeqNum =>
      shoebox.getLibrariesAndMembershipsChanged(lastSeqNum, maxItems).flatMap { lib =>

        shoebox.getLibrariesAndMembershipsChanged(lastSeqNum, maxItems).flatMap { libMembershipSeq =>
          db.readWriteAsync { implicit session =>
            libMembershipSeq.foreach(processLibraryMemberships)

            if (libMembershipSeq.length > 0) systemValueRepo.setSequenceNumber(SEQ_NUM_NAME, libMembershipSeq.map(_.library.seq).max)
            libMembershipSeq.length >= maxItems
          }
        }
      }
    }
  }
}
