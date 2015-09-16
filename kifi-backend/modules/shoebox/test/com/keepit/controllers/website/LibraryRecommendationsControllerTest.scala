package com.keepit.controllers.website

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.controller.{ FakeUserActionsHelper, FakeUserActionsModule }
import com.keepit.common.crypto.PublicIdConfiguration

import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.model.LibraryRecoInfo
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.{ Library, LibraryFactory, LibraryRecommendationFeedback, UserFactory }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{ JsArray, Json }
import play.api.test.FakeRequest
import play.api.test.Helpers._

class LibraryRecommendationsControllerTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  val modules = Seq(
    FakeShoeboxServiceModule(),
    FakeSearchServiceClientModule(),
    FakeShoeboxStoreModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHeimdalServiceClientModule(),
    FakeMailModule(),
    FakeCortexServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeUserActionsModule(),
    FakeActorSystemModule()
  )

  "LibraryRecommendationsController" should {
    "call updateLibraryRecommendationFeedback" in {
      withDb(modules: _*) { implicit injector =>
        implicit val pubIdCfg = inject[PublicIdConfiguration]

        val (lib1, user1) = db.readWrite { implicit rw =>
          val owner = UserFactory.user().saved
          val user1 = UserFactory.user().saved
          val lib = LibraryFactory.library().withOwner(owner).published().saved
          (lib, user1)
        }
        val libPubId = Library.publicId(lib1.id.get)
        val call = com.keepit.controllers.website.routes.LibraryRecommendationsController.updateLibraryRecommendationFeedback(libPubId)

        call.url === "/m/1/libraries/recos/feedback?id=" + libPubId.id
        call.method === "POST"

        inject[FakeUserActionsHelper].setUser(user1)
        val feedback = LibraryRecommendationFeedback(clicked = Some(true))
        val request = FakeRequest(call).withBody(Json.toJson(feedback))
        val resp1 = inject[LibraryRecommendationsController].updateLibraryRecommendationFeedback(libPubId)(request)
        status(resp1) === OK
      }
    }
  }
}
