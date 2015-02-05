package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class ActivityEmailRepoTest extends Specification with ShoeboxTestInjector {

  "ActivityEmailRepo" should {
    "save model" in {
      withDb() { implicit injector =>
        val unsavedActivityEmail = ActivityEmail(
          userId = Id[User](42),
          state = ActivityEmailStates.PENDING,
          otherFollowedLibraries = Some(Id[Library](2) :: Id[Library](4) :: Nil),
          userFollowedLibraries = Some(Id[Library](1) :: Id[Library](3) :: Nil),
          libraryRecommendations = Some(Id[Library](33) :: Nil)
        )
        val activityEmail = db.readWrite { implicit rw =>
          inject[ActivityEmailRepo].save(unsavedActivityEmail)
        }

        activityEmail.id must beSome
        activityEmail.userId === Id[User](42)
        activityEmail.libraryRecommendations must beSome(Id[Library](33) :: Nil)
        activityEmail.otherFollowedLibraries must beSome(Id[Library](2) :: Id[Library](4) :: Nil)
        activityEmail.userFollowedLibraries must beSome(Id[Library](1) :: Id[Library](3) :: Nil)
      }
    }
  }
}
