package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ShoeboxTestInjector}
import org.specs2.mutable.Specification

class NormalizedURICommanderTest extends Specification with ShoeboxTestInjector{

  val modules = Seq(
    //URISummaryCommanderTestModule(),
    FakeShoeboxServiceModule()
  )

  "normalizedURICommander" should {

    "return adult restriction status for uris" in {
      withInjector(modules: _*) { implicit injector =>
        val commander = Inject[NormalizedURICommander]
        val restrictionStatusOfURIs = commander.getAdultRestrictionOfURIs(Seq.empty)
      }
    }

  }
}
