package com.keepit.model

import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class ActivityEmailTest extends Specification with ShoeboxTestInjector {

  "ActivityEmailRepo" should {
    "save model" in {
      withDb() { implicit injector =>
        val unsavedActivityEmail = ActivityEmail(state = ActivityEmailStates.PENDING)
        val activityEmail = db.readWrite { implicit rw =>
          inject[ActivityEmailRepo].save(unsavedActivityEmail)
        }
        activityEmail.id must beSome
      }
    }
  }
}
