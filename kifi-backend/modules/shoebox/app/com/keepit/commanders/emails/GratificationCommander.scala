package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.commanders.emails.GratificationCommander.LibraryCountData
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.heimdal.HeimdalServiceClient
import com.keepit.model.{ LibraryRepo, LibraryMembershipRepo, User, Library }
import com.keepit.common.time._
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future

object GratificationCommander {

  case class LibraryCountData(totalCount: Int, countByLibrary: Map[Id[Library], Int])

}

class GratificationCommander @Inject() (
    db: Database,
    libMemRepo: LibraryMembershipRepo,
    libraryRepo: LibraryRepo,
    heimdal: HeimdalServiceClient) {

  private val NUM_WEEKS_BACK = 1

  def getLibraryFollowerCounts(userId: Id[User]): LibraryCountData = {
    db.readOnlyReplica { implicit s =>
      val since = currentDateTime.minusWeeks(NUM_WEEKS_BACK)
      val cnt = libMemRepo.userRecentFollowerCounts(userId, since)
      val cntMap = libMemRepo.userRecentTopFollowedLibrariesAndCounts(userId, since)
      LibraryCountData(cnt, cntMap)
    }
  }

  def getLibraryViewData(userId: Id[User]): Future[LibraryCountData] = {
    heimdal.getOwnerLibraryViewStats(userId).map {
      case (cnt, cntMap) =>
        db.readOnlyReplica { implicit s =>
          LibraryCountData(cnt, cntMap)
        }
    }
  }

}
