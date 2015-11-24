package com.keepit.eliza.commanders

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContext
import com.keepit.test.ElizaTestInjector
import org.specs2.mutable.SpecificationLike

class ElizaDiscussionCommanderTest extends TestKitSupport with SpecificationLike with ElizaTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeClockModule()
  )

  "ElizaDiscussionCommander" should {
    "serve up discussions by keep" in {
      "do anything" in {
        withDb(modules: _*) { implicit injector =>
          skipped("need to implement testing factories")
        }
      }
    }
  }
}
