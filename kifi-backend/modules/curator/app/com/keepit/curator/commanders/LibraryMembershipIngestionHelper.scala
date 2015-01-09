package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ SequenceNumber, State }
import com.keepit.common.logging.Logging
import com.keepit.curator.model.{ CuratorLibraryMembershipInfo, CuratorLibraryMembershipInfoRepo, CuratorLibraryMembershipInfoStates, LibraryRecommendationRepo, LibraryRecommendationStates }
import com.keepit.model.view.LibraryMembershipView
import com.keepit.model.{ LibraryMembership, Name, SystemValueRepo }
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

    libRecoRepo.getByLibraryAndUserId(libMembership.libraryId, libMembership.userId).
      filter(_.state == CuratorLibraryMembershipInfoStates.ACTIVE).map { reco =>
        // sets the state to inactive instead of followed to true so we know whether the reco was followed directly
        // from a recommendation or another way; does not set the state from inactive to active because it could've
        // been set to inactive for another reason (and it's possible they've already seen the library reco)
        if (0 == reco.delivered) {
          libRecoRepo.save(reco.copy(state = LibraryRecommendationStates.INACTIVE))
        } else {
          libRecoRepo.save(reco.copy(followed = true))
        }
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
