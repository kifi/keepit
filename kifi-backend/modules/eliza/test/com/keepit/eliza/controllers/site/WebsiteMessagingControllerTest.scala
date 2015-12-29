package com.keepit.eliza.controllers.site

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.crypto.{ FakeCryptoModule, PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.discussion.Message
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.eliza.model._
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ MessageFactory, MessageThreadFactory, _ }
import com.keepit.realtime.FakeAppBoyModule
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ DbInjectionHelper, ElizaInjectionHelpers, ElizaTestInjector }
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{ Json, JsValue }
import play.api.mvc.{ AnyContentAsEmpty, Call }
import play.api.test.FakeRequest
import play.api.test.Helpers._

class WebsiteMessagingControllerTest extends TestKitSupport with SpecificationLike with ElizaTestInjector with DbInjectionHelper with ElizaInjectionHelpers {
  def modules = Seq(
    FakeExecutionContextModule(),
    FakeSearchServiceClientModule(),
    ElizaCacheModule(),
    FakeShoeboxServiceModule(),
    FakeHeimdalServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeAppBoyModule(),
    FakeUserActionsModule(),
    FakeCryptoModule(),
    FakeElizaStoreModule(),
    FakeHttpClientModule(),
    FakeActorSystemModule(),
    FakeRoverServiceClientModule()
  )
  implicit def createFakeRequest(route: Call): FakeRequest[AnyContentAsEmpty.type] = FakeRequest(route.method, route.url)
  private def controller(implicit injector: Injector) = inject[WebsiteMessagingController]
  private def route = com.keepit.eliza.controllers.site.routes.WebsiteMessagingController

  "WebsiteMessagingController" should {
    "do nothing, now that all the logic has moved into ElizaDiscussionController" in {
      skipped(":)")
    }
  }
}
