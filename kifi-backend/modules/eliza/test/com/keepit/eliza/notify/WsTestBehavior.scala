package com.keepit.eliza.notify

import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.eliza.social.FakeSecureSocial
import com.keepit.model.{ UserExperimentType, UserExperiment, SocialUserInfo }
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, ShoeboxServiceClient }
import com.keepit.test.{ TestInjectorProvider, TestInjector }

trait WsTestBehavior { self: TestInjectorProvider =>

  def setupSocialUser(implicit injector: Injector): Unit = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.socialUserInfosByNetworkAndSocialId((FakeSecureSocial.FAKE_SOCIAL_ID, FakeSecureSocial.FAKE_NETWORK_TYPE)) = SocialUserInfo(
      userId = Some(Id(1)),
      fullName = FakeSecureSocial.FAKE_SOCIAL_USER.fullName,
      socialId = FakeSecureSocial.FAKE_SOCIAL_ID,
      networkType = FakeSecureSocial.FAKE_NETWORK_TYPE
    )
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
