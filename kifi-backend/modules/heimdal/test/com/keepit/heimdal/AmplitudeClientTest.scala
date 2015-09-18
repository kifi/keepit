package com.keepit.heimdal

import com.google.inject.Module
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.net.{ FakeHttpClientModule, ProdHttpClientModule }
import com.keepit.common.time._
import com.keepit.model.{ User, UserExperimentType }
import com.keepit.shoebox.{ FakeShoeboxServiceModule, FakeShoeboxServiceClientModule }
import com.keepit.social.NonUserKinds
import com.keepit.test.{ FakeWebServiceModule, HeimdalApplication, HeimdalApplicationInjector }
import org.specs2.mutable.Specification
import play.api.libs.json.JsString
import play.api.test.Helpers._

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

class AmplitudeClientTest extends Specification with HeimdalApplicationInjector {

  // change if you need to test events that are actually sent to an Amplitude project
  val sendEventsToAmplitude = false

  val modules: Seq[Module] =
    if (sendEventsToAmplitude) Seq(ProdHttpClientModule(), FakeAnalyticsModule(), FakeShoeboxServiceModule())
    else Seq(FakeHttpClientModule(), FakeWebServiceModule(), FakeAnalyticsModule(), FakeShoeboxServiceModule())

  val testUserId = Id[User](777)
  val testUserExternalId = ExternalId[User]("68a320e9-3c33-4b76-b577-d7cba0102745")

  def heimdalContext(data: (String, ContextData)*) = {
    val builder = new HeimdalContextBuilder
    builder += ("agentVersion", "1.2.3")
    builder += ("kifiInstallationId", "123")
    builder += ("userCreatedAt", "2015-06-22T06:59:01")
    builder.addExperiments(Set(UserExperimentType.ADMIN))
    builder ++= data.toMap
    builder.build
  }

  "AmplitudeClient" should {
    "send events" in running(new HeimdalApplication(modules: _*)) {
      val amplitude = inject[AmplitudeClient]
      val now = currentDateTime

      val userKept = new UserEvent(testUserId, heimdalContext(), UserEventTypes.KEPT, now)
      val userRecoAction = new UserEvent(testUserId, heimdalContext(), UserEventTypes.RECOMMENDATION_USER_ACTION, now)
      val userUsedKifi = new UserEvent(testUserId, heimdalContext(), UserEventTypes.USED_KIFI, now)
      val nonUserMessaged: NonUserEvent = new NonUserEvent("foo@bar.com", NonUserKinds.email, heimdalContext(), NonUserEventTypes.MESSAGED)
      val visitorViewedLib: VisitorEvent = new VisitorEvent(heimdalContext(), VisitorEventTypes.VIEWED_LIBRARY)
      val anonKept: AnonymousEvent = new AnonymousEvent(heimdalContext(), AnonymousEventTypes.KEPT)
      val pingdomEvent = new VisitorEvent(heimdalContext("userAgent" -> ContextStringData("Pingdom.com_bot_version_1.4_(http://www.pingdom.com/)")), VisitorEventTypes.VIEWED_LIBRARY)
      val userRegistered = new UserEvent(testUserId, heimdalContext("action" -> ContextStringData("registered")), UserEventTypes.JOINED, now)
      val userInstalled = new UserEvent(testUserId, heimdalContext("action" -> ContextStringData("installed")), UserEventTypes.JOINED, now)

      val userViewedPane = new UserEvent(testUserId, heimdalContext("type" -> ContextStringData("libraryChooser")), UserEventTypes.VIEWED_PANE, now)
      val userViewedPage1 = new UserEvent(testUserId, heimdalContext("type" -> ContextStringData("/josh")), UserEventTypes.VIEWED_PAGE, now)
      val userViewedPage2 = new UserEvent(testUserId, heimdalContext("type" -> ContextStringData("/settings")), UserEventTypes.VIEWED_PAGE, now)

      val visitorViewedPane1 = new VisitorEvent(heimdalContext("type" -> ContextStringData("login")), UserEventTypes.VIEWED_PANE, now)
      val visitorViewedPage1 = new VisitorEvent(heimdalContext("type" -> ContextStringData("signupLibrary")), UserEventTypes.VIEWED_PAGE, now)

      // any future that doesn't return the type we expect will throw an exception
      val eventsFList = List(
        // testing 2 events for the same user recorded at the same time
        amplitude.track(userKept) map {
          case dat: AmplitudeEventSent =>
            dat.eventData \\ "installation_id" === Seq(JsString("123"))
            dat.eventData \\ "created_at" === Seq(JsString("2015-06-22T06:59:01"))
        },
        amplitude.track(userKept) map { case _: AmplitudeEventSent => () },
        amplitude.track(userRecoAction) map { case _: AmplitudeEventSkipped => () },
        amplitude.track(userUsedKifi) map { case _: AmplitudeEventSkipped => () },
        amplitude.track(nonUserMessaged) map { case _: AmplitudeEventSent => () },
        amplitude.track(visitorViewedLib) map { case _: AmplitudeEventSent => () },
        amplitude.track(anonKept) map { case _: AmplitudeEventSkipped => () },
        amplitude.track(pingdomEvent) map { case _: AmplitudeEventSkipped => () },
        amplitude.track(userRegistered) map {
          case dat: AmplitudeEventSent =>
            dat.eventData \ "event_type" === JsString("user_registered")
        },
        amplitude.track(userInstalled) map {
          case dat: AmplitudeEventSent =>
            dat.eventData \ "event_type" === JsString("user_installed")
        },
        amplitude.track(userViewedPane) map {
          case dat: AmplitudeEventSent =>
            dat.eventData \ "event_type" === JsString("user_viewed_page")
            dat.eventData \\ "page_type" === Seq(JsString("pane"))
            dat.eventData \\ "type" === Seq(JsString("libraryChooser"))
        },
        amplitude.track(userViewedPage1) map { case dat: AmplitudeEventSkipped => () },
        amplitude.track(userViewedPage2) map {
          case dat: AmplitudeEventSent =>
            dat.eventData \ "event_type" === JsString("user_viewed_page")
            dat.eventData \\ "page_type" === Seq(JsString("page"))
            dat.eventData \\ "type" === Seq(JsString("settings"))
        },
        amplitude.track(userViewedPane) map {
          case dat: AmplitudeEventSent =>
            dat.eventData \ "event_type" === JsString("user_viewed_page")
            dat.eventData \\ "page_type" === Seq(JsString("pane"))
            dat.eventData \\ "type" === Seq(JsString("libraryChooser"))
        },
        amplitude.track(visitorViewedPane1) map {
          case dat: AmplitudeEventSent =>
            dat.eventData \ "event_type" === JsString("visitor_viewed_page")
            dat.eventData \\ "page_type" === Seq(JsString("pane"))
            dat.eventData \\ "type" === Seq(JsString("login"))
        },
        amplitude.track(visitorViewedPane1) map {
          case dat: AmplitudeEventSent =>
            dat.eventData \ "event_type" === JsString("visitor_viewed_page")
            dat.eventData \\ "page_type" === Seq(JsString("pane"))
            dat.eventData \\ "type" === Seq(JsString("login"))
        },
        amplitude.track(visitorViewedPage1) map {
          case dat: AmplitudeEventSent =>
            dat.eventData \ "event_type" === JsString("visitor_viewed_page")
            dat.eventData \\ "page_type" === Seq(JsString("modal"))
            dat.eventData \\ "type" === Seq(JsString("signupLibrary"))
        },
        amplitude.track(userViewedPage1) map { case _: AmplitudeEventSkipped => () },
        amplitude.track(userViewedPage2) map { case _: AmplitudeEventSent => () }
      )

      val eventsF = Future.sequence(eventsFList)
      val res = Await.result(eventsF, Duration("10 seconds"))
      res.size === eventsFList.size
    }

    "set user properties" in running(new HeimdalApplication(modules: _*)) {
      val amplitude = inject[AmplitudeClient]
      val apiF = amplitude.setUserProperties(testUserId, heimdalContext("userLastUpdated" -> ContextDoubleData(currentDateTime.getMillis / 1000)))
      Await.result(apiF, Duration("10 seconds")) match {
        case _: AmplitudeEventSent => success("yey")
        case e => failure("unexpected amplitude result: " + e.toString)
      }
    }

    "set user alias" in running(new HeimdalApplication(modules: _*)) {
      val amplitude = inject[AmplitudeClient]
      val apiF = amplitude.alias(testUserId, ExternalId[User]())
      Await.result(apiF, Duration("10 seconds")) match {
        case _: AmplitudeEventSent => success("yey")
        case e => failure("unexpected amplitude result: " + e.toString)
      }
    }
  }
}
