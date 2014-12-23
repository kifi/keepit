package com.keepit.controllers.website

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders
import com.keepit.commanders.{ ProcessedImageSize, LibraryCommander, FakeRecommendationsCommander, RecommendationsCommander }
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.controller.{ FakeUserActionsHelper, FakeUserActionsModule }
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.{ ImageSize, FakeShoeboxStoreModule }
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.{ CuratorServiceClient, FakeCuratorServiceClientImpl, FakeCuratorServiceClientModule }
import com.keepit.curator.model.{ LibraryRecoInfo, FullLibRecoInfo }
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.LibraryFactory
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactory
import com.keepit.model.UserFactoryHelper._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{ JsArray, Json }
import play.api.test.Helpers._
import play.api.test.FakeRequest

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class LibraryRecommendationsControllerTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  val modules = Seq(
    FakeShoeboxServiceModule(),
    FakeCuratorServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHeimdalServiceClientModule(),
    FakeMailModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeUserActionsModule(),
    FakeActorSystemModule()
  )

  "LibraryRecommendationsController" should {

    "call topLibRecos" in {
      withDb(modules: _*) { implicit injector =>
        val call = com.keepit.controllers.website.routes.LibraryRecommendationsController.topLibRecos()
        call.url === "/site/libraries/recos/top"
        call.method === "GET"

        val (libs, user1, user2) = db.readWrite { implicit rw =>
          val owner = UserFactory.user().saved
          (
            LibraryFactory.libraries(5).map(_.withUser(owner).published().saved),
            UserFactory.user().saved,
            UserFactory.user().saved
          )
        }

        val curator = inject[CuratorServiceClient].asInstanceOf[FakeCuratorServiceClientImpl]
        curator.topLibraryRecosExpectations(user2.id.get) = Seq(
          LibraryRecoInfo(user2.id.get, libs(2).id.get, 8, ""),
          LibraryRecoInfo(user2.id.get, libs(0).id.get, 7, ""),
          LibraryRecoInfo(user2.id.get, libs(1).id.get, 8, "")
        )

        inject[FakeUserActionsHelper].setUser(user1)
        val resp1 = inject[LibraryRecommendationsController].topLibRecos()(FakeRequest())
        status(resp1) === OK
        contentAsString(resp1) === "[]"

        inject[FakeUserActionsHelper].setUser(user2)
        val resp2 = inject[LibraryRecommendationsController].topLibRecos()(FakeRequest())
        status(resp2) === OK

        val jsArr = contentAsJson(resp2).asInstanceOf[JsArray]
        def itemStrVal(idx: Int, field: String) = (jsArr(idx) \ "itemInfo" \ field).as[String]
        itemStrVal(0, "name") === libs(2).name
        itemStrVal(1, "name") === libs(0).name
        itemStrVal(2, "name") === libs(1).name
      }
    }
  }
}
