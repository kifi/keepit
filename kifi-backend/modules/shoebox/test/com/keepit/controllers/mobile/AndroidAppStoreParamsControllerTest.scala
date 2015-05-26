package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller._

import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.helprank.HelpRankTestHelper
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.test.Helpers._
import play.api.test._

class AndroidAppStoreParamsControllerTest extends Specification with ShoeboxTestInjector with HelpRankTestHelper {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeCuratorServiceClientModule()
  )

  def prenormalize(url: String)(implicit injector: Injector): String = normalizationService.prenormalize(url).get

  "processAppStoreParams" in {
    withDb(controllerTestModules: _*) { implicit injector =>
      val (user1, user2) = db.readWrite { implicit s =>
        val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", username = Username("test1"), normalizedUsername = "test1"))
        val user2 = userRepo.save(User(firstName = "Eishay", lastName = "S", username = Username("test"), normalizedUsername = "test"))
        inject[UserValueRepo].getValueStringOpt(user1.id.get, UserValueName.KIFI_CAMPAIGN_ID).isDefined === false
        (user1, user2)
      }

      val path = com.keepit.controllers.mobile.routes.AndroidAppStoreParamsController.processAppStoreParams().url
      path === "/m/1/android/store/params"

      val userActionsHelper = inject[UserActionsHelper].asInstanceOf[FakeUserActionsHelper]
      userActionsHelper.setUser(user1, Set())
      val request = FakeRequest("POST", path + "?kcid=got_it")
      val result = inject[AndroidAppStoreParamsController].processAppStoreParams()(request)
      status(result) must equalTo(OK);

      db.readOnlyMaster { implicit s =>
        inject[UserValueRepo].getValueStringOpt(user1.id.get, UserValueName.KIFI_CAMPAIGN_ID).isDefined === true
      }

      val result2 = inject[AndroidAppStoreParamsController].processAppStoreParams()(request)
      status(result2) must equalTo(OK);

      db.readOnlyMaster { implicit s =>
        inject[UserValueRepo].getValueStringOpt(user1.id.get, UserValueName.KIFI_CAMPAIGN_ID).isDefined === true
      }
    }
  }

}
