package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.tracking.LibraryViewTrackingRepo
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import org.joda.time.DateTime.now
import com.keepit.test._

class LibraryViewTrackingRepoTest extends Specification with HeimdalTestInjector {
  // TODO: heimdal test injector is erroring "CreationException: Unable to create injector".
  "libraryViewTrackingRepo" should {
    "make queries without error" in {
      withDb() { implicit injector =>
        val libViewRepo = inject[LibraryViewTrackingRepo]
        val userId = Id[User](1)
        db.readOnlyMaster { implicit session =>
          val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
          libViewRepo.getTotalViews(ownerId = userId, since = t1)
          libViewRepo.getTopViewedLibrariesAndCounts(ownerId = userId, since = t1, limit = 1)
        }
        1 === 1
      }
    }
  }
}
