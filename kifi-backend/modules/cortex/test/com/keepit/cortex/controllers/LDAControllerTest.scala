package com.keepit.cortex.controllers

import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.aws.AwsDevModule
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.controllers.cortex.LDAController
import com.keepit.cortex.nlp.NLPDevModule
import com.keepit.cortex.{ CortexDevModelModule, CortexTestInjector }
import com.keepit.cortex.models.lda.LDAInfoStoreDevModule
import com.keepit.cortex.store.{ StatModelDevStoreModule, CommitInfoDevStoreModule, FeatureDevStoreModule, CortexCommonDevStoreModule }
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.model.{ Library, User }
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test.FakeRequest

class LDAControllerTest extends Specification with CortexTestInjector {
  val modules = Seq(
    FakeHealthcheckModule(),
    LDAInfoStoreDevModule(),
    AwsDevModule(),
    CortexCommonDevStoreModule(),
    CortexDevModelModule(),
    FakeCuratorServiceClientModule(),
    FakeActorSystemModule(),
    FeatureDevStoreModule(),
    CommitInfoDevStoreModule(),
    NLPDevModule(),
    StatModelDevStoreModule()
  )

  "LDAControllerTest" should {
    "userLibrariesScores works" in {
      withDb(modules: _*) { implicit injector =>
        val userId = Id[User](42)
        val call = com.keepit.controllers.cortex.routes.LDAController.userLibrariesScores(userId, None)
        call.url === "/internal/cortex/lda/userLibrariesScores?userId=42"
        call.method === "POST"

        val payload = Json.toJson(Seq(Id[Library](1), Id[Library](2)))
        val request = FakeRequest(call).withBody(payload)
        val result = inject[LDAController].userLibrariesScores(Id[User](42), None)(request)
        status(result) === OK
        contentAsString(result) === "[null,null]"
      }
    }
  }

}
