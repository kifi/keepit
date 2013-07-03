package com.keepit.common.analytics

import com.keepit.common.plugin._
import com.keepit.common.time._
import org.specs2.mutable._
import play.api.test.Helpers._
import com.keepit.test._
import play.api.libs.json.{JsObject, JsString}
import com.keepit.model._
import com.keepit.common.service.FortyTwoServices
import com.keepit.inject.ApplicationInjector

class EventListenerTest extends Specification with ApplicationInjector with InjectedDbRepos {

  def setup() = {
    db.readWrite {implicit s =>
      val normUrlId = uriRepo.save(NormalizedURIFactory("http://www.google.com/")).id.get
      val url = urlRepo.save(URLFactory(url = "http://www.google.com/", normalizedUriId = normUrlId))
      val user = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))
      val bookmark = bookmarkRepo.save(BookmarkFactory(
        title = "test",
        url = url,
        uriId = normUrlId,
        userId = user.id.get,
        source = BookmarkSource("HOVER_KEEP")
      ))
      (normUrlId, url, user, bookmark)
    }
  }

  "EventHelper" should {
    "parse search events" in {
      running(new DeprecatedShoeboxApplication().withShoeboxServiceModule) {
        val (normUrlId, url, user, bookmark) = setup()
        val listener = new EventListener(inject[UserRepo], inject[NormalizedURIRepo]) {
          val schedulingProperties = inject[SchedulingProperties]
          def onEvent: PartialFunction[Event,Unit] = { case _ => }
        }
        val (user2, result) = db.readWrite {implicit s =>
          listener.searchParser(user.externalId,
            JsObject(Seq("url" -> JsString("http://google.com/"), "query" -> JsString("potatoes"))),
            "kifiResultClicked"
          )
        }

        user2.id === user.id
        result.url === "http://google.com/"
        result.query === "potatoes"
      }
    }
  }

  "EventListener" should {
    "process events" in {
      running(new DeprecatedShoeboxApplication().withShoeboxServiceModule) {
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
