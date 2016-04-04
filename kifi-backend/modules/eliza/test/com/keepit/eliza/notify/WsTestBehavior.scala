package com.keepit.eliza.notify

import com.google.inject.Injector
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.db.Id
import com.keepit.model.view.UserSessionView
import com.keepit.model.{ User, UserExperimentType, UserExperiment }
import com.keepit.shoebox.model.ids.UserSessionExternalId
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, ShoeboxServiceClient }
import com.keepit.social.{ SocialNetworkType, SocialId }
import com.keepit.test.{ TestInjectorProvider }
import securesocial.core.IdentityId
import com.keepit.common.time._

object WsTestBehavior {
  val FAKE_SID = UserSessionExternalId("dc6cb121-2a69-47c7-898b-bc2b9356054c")

  val FAKE_SOCIAL_ID = SocialId("fake_id")

  val FAKE_NETWORK_TYPE = SocialNetworkType("facebook") // not fake so that it doesn't barf

  val FAKE_IDENTITY_ID = IdentityId(
    userId = FAKE_SOCIAL_ID.id,
    providerId = FAKE_NETWORK_TYPE.authProvider
  )

  val FAKE_SESSION = UserSessionView(FAKE_SOCIAL_ID, FAKE_NETWORK_TYPE, currentDateTime, true, currentDateTime, currentDateTime.plusYears(5))

}

trait WsTestBehavior { self: TestInjectorProvider =>

  val userId = Id[User](1)

  def setupUserIdentity(implicit injector: Injector): Unit = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveUserIdentity(WsTestBehavior.FAKE_IDENTITY_ID, userId)
    fakeShoeboxServiceClient.saveUserSession(WsTestBehavior.FAKE_SID, WsTestBehavior.FAKE_SESSION)
    inject[FakeUserActionsHelper].setUserId(Some(userId))
  }

  def setupUserExperiment(enabled: Boolean)(implicit injector: Injector): Unit = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.allUserExperiments(userId) =
      if (enabled) {
        Set(UserExperiment(
          userId = userId,
          experimentType = UserExperimentType.NEW_NOTIFS_SYSTEM
        ))
      } else {
        Set()
      }
  }

}
