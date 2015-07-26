package com.keepit.heimdal

import com.google.inject.Module
import com.keepit.common.db.Id
import com.keepit.common.net
import com.keepit.common.time._
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.model.{ UserExperimentType, User }
import com.keepit.social.NonUserKinds
import com.keepit.test.{ HeimdalApplication, HeimdalApplicationInjector }
import org.specs2.mutable.Specification
import play.api.test.Helpers._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class AmplitudeClientTest extends Specification with HeimdalApplicationInjector {
  val modules: Seq[Module] = Seq(
    DevAnalyticsModule(),
    net.ProdHttpClientModule()
  )

  "AmplitudeClient" should {
    args(skipAll = true)

    "send events" in running(new HeimdalApplication(modules: _*)) {
      val amplitudeClient = inject[AmplitudeClient]

      val now = currentDateTime
      val heimdalContext = {
        val builder = new HeimdalContextBuilder
        builder += ("fooBarBaz", "yay")
        builder += ("agentVersion", "1.2.3")
        builder.addExperiments(Set(UserExperimentType.ORGANIZATION, UserExperimentType.ADMIN))
        builder.build
      }

      val userEvent1 = new UserEvent(Id[User](777), heimdalContext, UserEventTypes.KEPT, now)
      val userEvent2 = new UserEvent(Id[User](777), heimdalContext, UserEventTypes.RECOMMENDATION_USER_ACTION, now)

      // testing 2 events for the same user recorded at the same time
      val track1 = amplitudeClient.track(userEvent1)
      val track2 = amplitudeClient.track(userEvent2)

      val nonUserEvent: NonUserEvent = new NonUserEvent("foo@bar.com", NonUserKinds.email, heimdalContext, NonUserEventTypes.MESSAGED)
      val track3 = amplitudeClient.track(nonUserEvent)

      val visitorEvent: VisitorEvent = new VisitorEvent(heimdalContext, VisitorEventTypes.VIEWED_LIBRARY)
      val track4 = amplitudeClient.track(visitorEvent)

      Await.result(track1, Duration("5 seconds"))
      Await.result(track2, Duration("5 seconds"))
      Await.result(track3, Duration("5 seconds"))
      Await.result(track4, Duration("5 seconds"))

      1 === 1
    }
  }
}
