package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ SequenceNumber, State }
import com.keepit.common.logging.Logging
import com.keepit.curator.model.{ CuratorLibraryMembershipInfoStates, LibraryRecommendationRepo, CuratorLibraryMembershipInfo, CuratorLibraryMembershipInfoRepo }
import com.keepit.model.view.LibraryMembershipView
import com.keepit.model.{ LibraryMembershipStates, LibraryMembership, Name, SystemValueRepo }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

class LibraryMembershipIngestionHelper @Inject() (
    systemValueRepo: SystemValueRepo,
    libInfoRepo: CuratorLibraryMembershipInfoRepo,
    libRecoRepo: LibraryRecommendationRepo,
    db: Database,
    shoebox: ShoeboxServiceClient) extends GlobalSeedIngestionHelper with Logging {

  private val SEQ_NUM_NAME: Name[SequenceNumber[LibraryMembership]] = Name("library_membership_seq_num")

  private def processLibraryMemberships(libMembership: LibraryMembershipView)(implicit session: RWSession): Unit = {
    val membershipInfo = libInfoRepo.getByUserAndLibraryId(libMembership.userId, libMembership.libraryId).map { libMembershipInfo =>
      libInfoRepo.save(libMembershipInfo.copy(
        userId = libMembership.userId,
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

    libRecoRepo.getByLibraryAndUserId(libMembership.libraryId, libMembership.userId).map { reco =>
      libRecoRepo.save(reco.copy(followed = membershipInfo.state == CuratorLibraryMembershipInfoStates.ACTIVE))
    }
  }

  def apply(maxItems: Int): Future[Boolean] = {
    val lastSeqNumFut: Future[SequenceNumber[LibraryMembership]] = db.readOnlyMasterAsync { implicit session =>
      systemValueRepo.getSequenceNumber(SEQ_NUM_NAME) getOrElse { SequenceNumber[LibraryMembership](0) }
    }

    lastSeqNumFut.flatMap { lastSeqNum =>
      shoebox.getLibraryMembershipsChanged(lastSeqNum, maxItems).flatMap { libMemberships =>
        db.readWriteAsync { implicit session =>
          libMemberships.foreach(processLibraryMemberships)
          if (libMemberships.length > 0) systemValueRepo.setSequenceNumber(SEQ_NUM_NAME, libMemberships.map(_.seq).max)
          libMemberships.length >= maxItems
        }
      }
    }
  }
}
