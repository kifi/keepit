package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.heimdal.HeimdalServiceClient
import com.keepit.model.{ LibraryRepo, LibraryMembershipRepo, User, Library }
import com.keepit.common.time._
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future

class GratificationCommander @Inject() (
    db: Database,
    libMemRepo: LibraryMembershipRepo,
    libraryRepo: LibraryRepo,
    heimdal: HeimdalServiceClient) {

  type LibraryCountData = (Int, Map[Library, Int]) // (total_count, sub_counts)

  private val NUM_WEEKS_BACK = 1

  def getLibraryFollowerCounts(userId: Id[User]): LibraryCountData = {
    db.readOnlyReplica { implicit s =>
      val since = currentDateTime.minusWeeks(NUM_WEEKS_BACK)
      val cnt = libMemRepo.userRecentFollowerCounts(userId, since)
      val cntMap = libMemRepo.userRecentTopFollowedLibrariesAndCounts(userId, since)
      (cnt, cntMap.map { case (id, n) => (libraryRepo.get(id), n) })
    }
  }

  def getLibraryViewData(userId: Id[User]): Future[LibraryCountData] = {
    heimdal.getOwnerLibraryViewStats(userId).map {
      case (cnt, cntMap) =>
        db.readOnlyReplica { implicit s =>
          (cnt, cntMap.map { case (id, n) => (libraryRepo.get(id), n) })
        }
    }
  }

}
