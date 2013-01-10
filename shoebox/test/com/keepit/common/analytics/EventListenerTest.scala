package com.keepit.common.analytics

import com.keepit.model.NormalizedURI.States._
import com.keepit.common.time._
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.test._
import play.api.test.Helpers._
import scala.math._
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import com.keepit.test.EmptyApplication
import com.keepit.common.db.Id
import play.api.libs.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString}
import com.keepit.model._
import com.keepit.common.db._
import play.api.Play.current

@RunWith(classOf[JUnitRunner])
class EventListenerTest extends SpecificationWithJUnit {

  def setup() = {
    CX.withConnection { implicit conn =>
      val normUrlId = NormalizedURI("http://www.google.com/").save.id.get
      val url = URL("http://www.google.com/", normUrlId).save
      val user = User(firstName = "Andrew", lastName = "Conner").save
      val bookmark = Bookmark(
        title = "test",
        url = url,
        uriId = normUrlId,
        userId = user.id.get,
        source = BookmarkSource("HOVER_KEEP")
      ).save
      (normUrlId, url, user, bookmark)
    }
  }

  "EventHelper" should {
    "parse search events" in {
      running(new EmptyApplication()) {
        val (normUrlId, url, user, bookmark) = setup()
        val (user2, result) = CX.withConnection { implicit conn => EventHelper.searchParser(user.externalId, JsObject(Seq("url" -> JsString("http://google.com/"), "query" -> JsString("potatoes")))) }

        user2.id === user.id
        result.url === "http://google.com/"
        result.query === "potatoes"
      }
    }
  }

  "EventListener" should {
    "process events" in {
      running(new EmptyApplication()) {
        val (normUrlId, url, user, bookmark) = setup()

        val unrelatedEvent = Events.userEvent(EventFamilies.SEARCH,"someOtherEvent", user, Seq(), "", JsObject(Seq()), Seq())

        val event = Events.userEvent(EventFamilies.SEARCH,"kifiResultClicked", user, Seq(), "", JsObject(Seq()), Seq())

        EventListener.newEvent(unrelatedEvent) === Seq()
        EventListener.newEvent(event) === Seq("KifiResultClickedListener")

      }
    }

  }
}
