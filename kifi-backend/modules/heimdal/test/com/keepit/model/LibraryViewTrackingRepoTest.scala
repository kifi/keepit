package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.model.tracking.LibraryViewTrackingRepo
import com.keepit.test.HeimdalTestInjector
import org.specs2.mutable.Specification
import org.joda.time.DateTime.now

class LibraryViewTrackingRepoTest extends Specification with HeimdalTestInjector {
  // TODO: heimdal test injector is erroring "CreationException: Unable to create injector".
  /*
  "libraryViewTrackingRepo" should {
    "make queries without error" in {
      withDb() { implicit injector =>
        val libViewRepo = inject[LibraryViewTrackingRepo]
        val userId = Id[User](1)
        db.readOnlyMaster { implicit session =>
          libViewRepo.getTotalViews(ownerId = userId, since = now)
          libViewRepo.getTopViewedLibrariesAndCounts(ownerId = userId, since = now, limit = 1)
        }
        1 === 1
      }
    }
  }
  */
}
