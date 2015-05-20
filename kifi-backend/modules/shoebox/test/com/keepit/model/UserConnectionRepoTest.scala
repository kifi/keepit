package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import org.joda.time.DateTime.now

class UserConnectionRepoTest extends Specification with ShoeboxTestInjector {
  "userConnectionRepo" should {
    "make queries without error" in {
      withDb() { implicit injector =>
        val userConnectionRepo = inject[UserConnectionRepo]
        val userId = Id[User](1)
        db.readWrite { implicit session =>
          userConnectionRepo.getConnectionsSince(userId, now)
        }
        1 === 1
      }
    }
  }
}