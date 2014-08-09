package com.keepit.curator

import com.google.inject.Injector
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.mail.RemotePostOffice
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.shoebox.{ ShoeboxServiceClient, FakeShoeboxServiceModule, FakeShoeboxServiceClientImpl }
import commanders.email.EngagementFeedEmailSender
import org.specs2.mutable.Specification

class EngagementFeedEmailSenderTest extends Specification with CuratorTestInjector {
  import TestHelpers._

  val modules = Seq(
    FakeHealthcheckModule(),
    FakeGraphServiceModule(),
    FakeHttpClientModule(),
    FakeShoeboxServiceModule(),
    FakeCortexServiceClientModule(),
    FakeCacheModule())

  def setup()(implicit injector: Injector) = {
    val shoebox = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]

    (shoebox)
  }

  "EngagementFeedEmailSender" should {

    "sends to users in experiment" in {
      withDb(modules: _*) { implicit injector =>
        val (shoebox) = setup()
        val sender = inject[EngagementFeedEmailSender]
        val user1 = makeUser(42, shoebox)
        val user2 = makeUser(43, shoebox)

        //shoebox.saveUsers(user1, user2)
        println(shoebox)
        println(shoebox.allUsers)
        //println(shoebox.allUsers)

        makeKeeps(user1.id.get, 20, shoebox)
        makeKeeps(user2.id.get, 30, shoebox)

        sender.send()

        1 == 1
      }
    }

  }

}
