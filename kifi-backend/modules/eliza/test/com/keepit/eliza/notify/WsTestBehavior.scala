package com.keepit.eliza.notify

import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.eliza.social.FakeSecureSocial
import com.keepit.model.{ User, UserExperimentType, UserExperiment, SocialUserInfo }
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, ShoeboxServiceClient }
import com.keepit.test.{ TestInjectorProvider, TestInjector }
import securesocial.core.IdentityId

trait WsTestBehavior { self: TestInjectorProvider =>

  def setupUserIdentity(implicit injector: Injector): Unit = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveUserIdentity(FakeSecureSocial.FAKE_IDENTITY_ID, FakeSecureSocial.fakeUserIdentity(Some(Id(1))))
  }

  def setupUserExperiment(enabled: Boolean)(implicit injector: Injector): Unit = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.allUserExperiments(Id(1)) =
      if (enabled) {
        Set(UserExperiment(
          userId = Id(1),
          experimentType = UserExperimentType.NEW_NOTIFS_SYSTEM
        ))
      } else {
        Set()
      }
  }

}
