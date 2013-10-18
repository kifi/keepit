package com.keepit.common.analytics

import com.keepit.common.plugin._
import com.keepit.common.time._
import org.specs2.mutable._
import play.api.test.Helpers._
import com.keepit.test._
import play.api.libs.json.{JsObject, JsString}
import com.keepit.model._
import com.keepit.common.service.FortyTwoServices
import com.google.inject.Injector
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.search.{SearchServiceClient, TestSearchServiceClientModule}
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.eliza.TestElizaServiceClientModule
import com.keepit.common.db.slick.Database

class EventListenerTest extends Specification with ShoeboxApplicationInjector {

  val eventListenerTestModules = Seq(TestActorSystemModule(), ShoeboxFakeStoreModule(), TestAnalyticsModule(), TestSearchServiceClientModule(), FakeShoeboxServiceModule(), TestElizaServiceClientModule())

  def setup()(implicit injector: Injector) = {
    db.readWrite {implicit s =>
      val normUrl = uriRepo.save(NormalizedURI.withHash("http://www.google.com/"))
      val url = urlRepo.save(URLFactory(url = "http://www.google.com/", normalizedUriId = normUrl.id.get))
      val user = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))
      val bookmark = bookmarkRepo.save(BookmarkFactory(
        uri = normUrl,
        userId = user.id.get,
        title = Some("test"),
        url = url,
        source = BookmarkSource("HOVER_KEEP")
      ))
      (normUrl.id.get, url, user, bookmark)
    }
  }

  "EventListener" should {
    "process events" in {
      running(new ShoeboxApplication(eventListenerTestModules:_*)) {
        val (normUrlId, url, user, bookmark) = setup()
        implicit val clock = inject[Clock]
        implicit val fortyTwoServices = inject[FortyTwoServices]

        val unrelatedEvent = Events.userEvent(EventFamilies.SEARCH,"someOtherEvent", user, Set(), "", JsObject(Seq()), Seq())

        val kifiEvent = Events.userEvent(EventFamilies.SEARCH,"kifiResultClicked", user, Set(), "", JsObject(Seq()), Seq())
        val googleEvent = Events.userEvent(EventFamilies.SEARCH,"googleResultClicked", user, Set(), "", JsObject(Seq()), Seq())

        inject[EventHelper].matchEvent(unrelatedEvent) === Seq()
        inject[EventHelper].matchEvent(kifiEvent) === Seq("ResultClickedListener")
        inject[EventHelper].matchEvent(googleEvent) === Seq("ResultClickedListener")
      }
    }

  }
}
