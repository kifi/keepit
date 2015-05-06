package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class ActivityEmailRepoTest extends Specification with ShoeboxTestInjector {

  "ActivityEmailRepo" should {
    "save model" in {
      withDb() { implicit injector =>
        val (activityEmail, user1) = db.readWrite { implicit rw =>
          val u1 = user().withName("Kifi", "User1").withEmailAddress("u1@kifi.com").saved
          val unsavedActivityEmail = ActivityEmail(
            userId = u1.id.get,
            state = ActivityEmailStates.PENDING,
            otherFollowedLibraries = Some(Id[Library](2) :: Id[Library](4) :: Nil),
            userFollowedLibraries = Some(Id[Library](1) :: Id[Library](3) :: Nil),
            libraryRecommendations = Some(Id[Library](33) :: Nil)
          )

          (inject[ActivityEmailRepo].save(unsavedActivityEmail), u1)
        }

        activityEmail.id must beSome
        activityEmail.userId === user1.id.get
        activityEmail.libraryRecommendations must beSome(Id[Library](33) :: Nil)
        activityEmail.otherFollowedLibraries must beSome(Id[Library](2) :: Id[Library](4) :: Nil)
        activityEmail.userFollowedLibraries must beSome(Id[Library](1) :: Id[Library](3) :: Nil)
      }
    }
  }
}
