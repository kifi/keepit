package com.keepit.commanders.emails.tips

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class ImportGmailContactsTipTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHealthcheckModule(),
    FakeGraphServiceModule(),
    FakeCortexServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeCacheModule(),
    FakeElizaServiceClientModule(),
    FakeABookServiceClientModule())

  "ImportGmailContactsTip" should {
    "returns email HTML" in {
      pending("needs design")
    }
  }

}
