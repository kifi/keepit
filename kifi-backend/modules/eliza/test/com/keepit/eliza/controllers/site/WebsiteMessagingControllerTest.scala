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
import play.api.libs.json.JsValue
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
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def controller(implicit injector: Injector) = inject[WebsiteMessagingController]
  private def route = com.keepit.eliza.controllers.site.routes.WebsiteMessagingController

  "WebsiteMessagingController" should {
    "page through a keep's messages" in {
      withDb(modules: _*) { implicit injector =>
        val keepId = Id[Keep](1)
        val (user, thread, messages) = db.readWrite { implicit session =>
          val user = UserFactory.user().get
          val thread = MessageThreadFactory.thread().withKeep(keepId).saved
          val messages = MessageFactory.messages(20).map(_.withThread(thread).saved)
          (user, thread, messages)
        }
        val keepPubId = Keep.publicId(keepId)

        val pageSize = 3
        val expectedPages = messages.sortBy(m => (m.createdAt.getMillis, m.id.get.id)).reverse.map(_.id.get).grouped(pageSize).toList

        inject[FakeUserActionsHelper].setUser(user)
        val actualPages = Iterator.iterate(Seq.empty[PublicId[Message]]) { prevPage =>
          val fromIdOpt = prevPage.lastOption.map(_.id)
          val request = route.getMessagesOnKeep(keepPubId, pageSize, fromIdOpt)
          val response = controller.getMessagesOnKeep(keepPubId, pageSize, fromIdOpt)(request)
          val items = (contentAsJson(response) \ "messages").as[Seq[JsValue]]
          items.map { j => (j \ "id").as[PublicId[Message]] }
        }.toStream.tail.takeWhile(_.nonEmpty).toList

        actualPages.length === expectedPages.length
        (actualPages zip expectedPages) foreach {
          case (actual, expected) =>
            actual.map(pubId => ElizaMessage.fromCommon(Message.decodePublicId(pubId).get)) === expected
        }
        1 === 1
      }
    }
  }
}
