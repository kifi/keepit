package com.keepit.heimdal

import java.util.concurrent.atomic.AtomicLong

import com.google.inject.Module
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.net.{ FakeHttpClientModule, ProdHttpClientModule }
import com.keepit.common.time._
import com.keepit.model.{ User, UserExperimentType }
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.NonUserKinds
import com.keepit.test.{ DummyRequestHeader, FakeWebServiceModule, HeimdalApplication, HeimdalApplicationInjector }
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

  val requestId = new AtomicLong(0)

  def heimdalContext(data: (String, ContextData)*) = {
    val builder = new HeimdalContextBuilder
    builder += ("agentVersion", "1.2.3")
    builder += ("kifiInstallationId", "123")
    builder += ("userCreatedAt", "2015-06-22T06:59:01")
    builder.addExperiments(Set(UserExperimentType.ADMIN))
    builder ++= data.toMap
    builder.build
  }

  def contextWithRequestHeader(data: (String, ContextData)*)(f: (DummyRequestHeader) => Unit) = {
    val builder = new HeimdalContextBuilder
    builder ++= data.toMap
    val reqHeader = DummyRequestHeader(requestId.getAndIncrement(), "/")
    reqHeader.mutableHeaders.append(("X-Forwarded-For", Seq("192.168.1.1")))
    f(reqHeader)
    builder.addRequestInfo(reqHeader)
    builder.build
  }

  def setAmplitudeCookie(requestHeader: DummyRequestHeader, value: String): Unit = {
    requestHeader.mutableCookies("amplitude_idkifi.com") = value
  }

  "AmplitudeClient" should {
    "send events" in running(new HeimdalApplication(modules: _*)) {
      val amplitude = inject[AmplitudeClient]
      val now = currentDateTime

      val userKept = new UserEvent(testUserId, heimdalContext("os" -> ContextStringData("Windows 95"), "client" -> ContextStringData("androidApp")), UserEventTypes.KEPT, now)
      val userRecoAction = new UserEvent(testUserId, heimdalContext(), UserEventTypes.RECOMMENDATION_USER_ACTION, now)
      val userUsedKifi = new UserEvent(testUserId, heimdalContext(), UserEventTypes.USED_KIFI, now)
      val nonUserMessaged: NonUserEvent = new NonUserEvent("foo@bar.com", TrackingNonUserKind.email, heimdalContext(), NonUserEventTypes.MESSAGED)
      val visitorViewedLib: VisitorEvent = new VisitorEvent(heimdalContext(), VisitorEventTypes.VIEWED_LIBRARY)
      val anonKept: AnonymousEvent = new AnonymousEvent(heimdalContext(), AnonymousEventTypes.KEPT)
      val pingdomEvent = new VisitorEvent(heimdalContext("userAgent" -> ContextStringData("Pingdom.com_bot_version_1.4_(http://www.pingdom.com/)")), VisitorEventTypes.VIEWED_LIBRARY)
      val userRegistered = new UserEvent(testUserId, heimdalContext("action" -> ContextStringData("registered")), UserEventTypes.JOINED, now)
      val userInstalled = new UserEvent(testUserId, heimdalContext("action" -> ContextStringData("installedExtension")), UserEventTypes.JOINED, now)

      val userViewedPane = new UserEvent(testUserId, heimdalContext("type" -> ContextStringData("libraryChooser")), UserEventTypes.VIEWED_PANE, now)
      val userViewedPage1 = new UserEvent(testUserId, heimdalContext("type" -> ContextStringData("/josh")), UserEventTypes.VIEWED_PAGE, now)
      val userViewedPage2 = new UserEvent(testUserId, heimdalContext("type" -> ContextStringData("/settings")), UserEventTypes.VIEWED_PAGE, now)

      val contextWithInvalidJsonAmplitudeCookie = contextWithRequestHeader("type" -> ContextStringData("/?m=0")) { reqHeader =>
        setAmplitudeCookie(reqHeader, "a2VlcGl0") // keepit
      }
      val userViewedPage3 = new UserEvent(testUserId, contextWithInvalidJsonAmplitudeCookie, UserEventTypes.VIEWED_PAGE, now)

      val visitorViewedPane1 = new VisitorEvent(heimdalContext("type" -> ContextStringData("login")), UserEventTypes.VIEWED_PANE, now)
      val visitorViewedPage1 = new VisitorEvent(heimdalContext("type" -> ContextStringData("signupLibrary")), UserEventTypes.VIEWED_PAGE, now)
      val visitorViewedPage2 = new VisitorEvent(heimdalContext("type" -> ContextStringData("install")), UserEventTypes.VIEWED_PAGE, now)

      val contextWithValidAmplitudeCookie = contextWithRequestHeader("action" -> ContextStringData("spamreport")) { reqHeader =>
        // {"deviceId":"12345678-1234-4567-1234-123412341234","userId":null,"optOut":false}
        setAmplitudeCookie(reqHeader, "eyJkZXZpY2VJZCI6IjEyMzQ1Njc4LTEyMzQtNDU2Ny0xMjM0LTEyMzQxMjM0MTIzNCIsInVzZXJJZCI6bnVsbCwib3B0T3V0IjpmYWxzZX0=")
      }
      val userWasNotified1 = new UserEvent(testUserId, contextWithValidAmplitudeCookie, UserEventTypes.WAS_NOTIFIED, now)

      val contextWithNonBase64AmplitudeCookie = contextWithRequestHeader("action" -> ContextStringData("deferred")) { reqHeader =>
        setAmplitudeCookie(reqHeader, "*(&#@($&(@#$&@#$")
      }
      val userWasNotified2 = new UserEvent(testUserId, contextWithNonBase64AmplitudeCookie, UserEventTypes.WAS_NOTIFIED, now)

      // any future that doesn't return the type we expect will throw an exception
      val eventsFList = List(
        // testing 2 events for the same user recorded at the same time
        amplitude.track(userKept) map {
          case dat: AmplitudeEventSent =>
            dat.eventData \\ "installation_id" === Seq(JsString("123"))
            dat.eventData \\ "created_at" === Seq(JsString("2015-06-22T06:59:01"))
            dat.eventData \ "os_name" === JsString("Windows 95")
            dat.eventData \\ "operating_system" === Seq(JsString("Windows 95"))
            // test transforming camelCase values to snake_case
            dat.eventData \ "platform" === JsString("android_app")
            dat.eventData \\ "client" === Seq(JsString("android_app"))
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
            dat.eventData \\ "type" === Seq(JsString("library_chooser"))
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
            dat.eventData \\ "type" === Seq(JsString("library_chooser"))
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
            dat.eventData \\ "type" === Seq(JsString("signup_library"))
        },
        amplitude.track(visitorViewedPage2) map {
          case dat: AmplitudeEventSent =>
            dat.eventData \ "event_type" === JsString("visitor_viewed_page")
            dat.eventData \\ "page_type" === Seq(JsString("page"))
            dat.eventData \\ "type" === Seq(JsString("install"))
        },
        amplitude.track(userViewedPage1) map { case _: AmplitudeEventSkipped => () },
        amplitude.track(userViewedPage2) map { case _: AmplitudeEventSent => () },
        amplitude.track(userViewedPage3) map {
          case dat: AmplitudeEventSent =>
            dat.eventData \\ "type" === Seq(JsString("home_feed:successful_signup"))
        },
        amplitude.track(userWasNotified1) map {
          case dat: AmplitudeEventSent =>
            // event name should have been renamed
            dat.eventData \ "event_type" === JsString("user_clicked_notification")
            // device_id should be fetched from the amplitude base64-encoded cookie
            dat.eventData \ "device_id" === JsString("12345678-1234-4567-1234-123412341234")
        }
      )

      val eventsF = Future.sequence(eventsFList)

      // send the same event twice to test that insert_id are identical
      val dupeEvents = List(amplitude.track(userWasNotified2), amplitude.track(userWasNotified2))
      val dupeEventsF = Future.sequence(dupeEvents).map {
        case List(evt1: AmplitudeEventSent, evt2: AmplitudeEventSent) =>
          val (JsString(insertId1), JsString(insertId2)) = (evt1.eventData \ "insert_id", evt2.eventData \ "insert_id")
          insertId1 must matching("[a-z0-9]{32}".r) // expect md5 hash
          insertId1 === insertId2 // expect duplicate events to have the same insert_id property

          evt1.eventData \ "event_type" === JsString("user_was_notified")
          // default device_id for a user event is user_<user_id>
          evt1.eventData \ "device_id" === JsString("user_777")
          evt1 === evt2 // sanity check, the insert_id check above should be enough
      }

      val res = Await.result(eventsF, Duration("10 seconds"))
      res.size === eventsFList.size

      val res2 = Await.result(dupeEventsF, Duration("5 seconds"))
      res2.isSuccess must beTrue
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
