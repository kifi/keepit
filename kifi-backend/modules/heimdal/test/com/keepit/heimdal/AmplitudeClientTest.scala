package com.keepit.heimdal

import com.google.inject.Module
import com.keepit.common.db.Id
import com.keepit.common.net.{ FakeHttpClientModule, ProdHttpClientModule }
import com.keepit.common.time._
import com.keepit.model.{ User, UserExperimentType }
import com.keepit.social.NonUserKinds
import com.keepit.test.{ HeimdalApplication, HeimdalApplicationInjector }
import org.specs2.mutable.Specification
import play.api.test.Helpers._

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

class AmplitudeClientTest extends Specification with HeimdalApplicationInjector {

  // change if you need to test events that are actually sent to an Amplitude project
  val sendEventsToAmplitude = false

  val modules: Seq[Module] =
    if (sendEventsToAmplitude) Seq(ProdHttpClientModule(), ProdAmplitudeTransportModule(), DevAnalyticsModule())
    else Seq(FakeHttpClientModule(), DevAmplitudeTransportModule(), DevAnalyticsModule())

  // simple helpers
  def expect(expectation: Boolean, fut: Future[Boolean], reason: String): Future[Either[String, String]] = {
    val msg = s"$reason [expected $expectation]"
    fut map {
      case bool if expectation == bool => Right(msg)
      case _ => Left(msg)
    }
  }

  def expectT(fut: Future[Boolean], reason: String): Future[Either[String, String]] = expect(true, fut, reason)
  def expectF(fut: Future[Boolean], reason: String): Future[Either[String, String]] = expect(false, fut, reason)

  def heimdalContext(data: (String, ContextData)*) = {
    val builder = new HeimdalContextBuilder
    builder += ("fooBarBaz", "yay")
    builder += ("agentVersion", "1.2.3")
    builder.addExperiments(Set(UserExperimentType.ORGANIZATION, UserExperimentType.ADMIN))
    builder ++= data.toMap
    builder.build
  }

  "AmplitudeClient" should {
    "send events" in running(new HeimdalApplication(modules: _*)) {
      val amplitudeClient = inject[AmplitudeClient]
      val now = currentDateTime

      val userKept = new UserEvent(Id[User](777), heimdalContext(), UserEventTypes.KEPT, now)
      val userRecoAction = new UserEvent(Id[User](777), heimdalContext(), UserEventTypes.RECOMMENDATION_USER_ACTION, now)
      val userUsedKifi = new UserEvent(Id[User](777), heimdalContext(), UserEventTypes.USED_KIFI, now)
      val nonUserMessaged: NonUserEvent = new NonUserEvent("foo@bar.com", NonUserKinds.email, heimdalContext(), NonUserEventTypes.MESSAGED)
      val visitorViewedLib: VisitorEvent = new VisitorEvent(heimdalContext(), VisitorEventTypes.VIEWED_LIBRARY)
      val anonKept: AnonymousEvent = new AnonymousEvent(heimdalContext(), AnonymousEventTypes.KEPT)
      val pingdomEvent = new VisitorEvent(heimdalContext("userAgent" -> ContextStringData("Pingdom.com_bot_version_1.4_(http://www.pingdom.com/)")), VisitorEventTypes.VIEWED_LIBRARY)

      val eventsWithErrorsF = Future.sequence(List(
        // testing 2 events for the same user recorded at the same time
        expectT(amplitudeClient.track(userKept), "user kept 1"),
        expectT(amplitudeClient.track(userKept), "user kept 2"),
        expectF(amplitudeClient.track(userRecoAction), "user_reco_action should not be sent"),
        expectF(amplitudeClient.track(userUsedKifi), "user_used_kifi should not be sent"),
        expectT(amplitudeClient.track(nonUserMessaged), "non user msg"),
        expectT(amplitudeClient.track(visitorViewedLib), "visitor viewed lib"),
        expectF(amplitudeClient.track(anonKept), "anon kept event should be skipped"),
        expectF(amplitudeClient.track(pingdomEvent), "pingdom event should be skipped")
      )) map { results =>
        results.filter(_.isLeft)
      }

      val eventsWithErrors = Await.result(eventsWithErrorsF, Duration("10 seconds"))
      eventsWithErrors must beEmpty
    }
  }
}
