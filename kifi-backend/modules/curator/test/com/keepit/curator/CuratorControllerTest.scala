package com.keepit.curator

import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.controllers.internal.CuratorController
import com.keepit.curator.model.{ LibraryRecommendationRepo, RecommendationSource, RecommendationSubSource }
import com.keepit.curator.queue.FakeFeedDigestEmailQueueModule
import com.keepit.curator.store.FakeCuratorStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ Library, LibraryRecommendationFeedback, User }
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

class CuratorControllerTest extends Specification with CuratorTestInjector with CuratorTestHelpers {

  def modules = Seq(
    FakeActorSystemModule(),
    FakeFeedDigestEmailQueueModule(),
    FakeElizaServiceClientModule(),
    FakeExecutionContextModule(),
    FakeHttpClientModule(),
    FakeGraphServiceModule(),
    FakeCortexServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeRoverServiceClientModule(),
    FakeCuratorStoreModule(),
    FakeHealthcheckModule())

  "CuratorController" should {
    "update library feedback" in {
      withDb(modules: _*) { implicit injector =>
        val userId = Id[User](1)
        val libId = Id[Library](42)
        val libRecRepo = inject[LibraryRecommendationRepo]
        db.readWrite { implicit rw => libRecRepo.save(makeLibraryRecommendation(42, 1, 8)) }

        val call = com.keepit.curator.controllers.internal.routes.CuratorController.updateLibraryRecommendationFeedback(userId, libId)
        call.method === "POST"
        call.url === s"/internal/curator/updateLibraryRecommendationFeedback?userId=1&libraryId=42"

        val payload = Json.toJson(LibraryRecommendationFeedback(clicked = Some(true), trashed = Some(true), followed = Some(true), vote = Some(false),
          source = Some(RecommendationSource.Site), subSource = Some(RecommendationSubSource.RecommendationsFeed)))

        val request = FakeRequest(call).withBody(payload)
        val result = inject[CuratorController].updateLibraryRecommendationFeedback(userId, libId)(request)
        status(result) === OK
        contentAsString(result) === "true"

        db.readOnlyMaster { implicit s =>
          val actual = libRecRepo.getByLibraryAndUserId(libId, userId).get
          actual.followed === true
          actual.clicked === 1
          actual.trashed === true
          actual.vote.get === false
        }
      }
    }
  }

}
