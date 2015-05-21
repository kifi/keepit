package com.keepit.controllers.core

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule

import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class OAuth2ControllerTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(FakeShoeboxServiceModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeHttpClientModule(),
    FakeMailModule(),
    FakeSearchServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeCuratorServiceClientModule())

  "OAuth2Controller" should {
    "handle state token" in {
      val tk = "%7B%22token%22:%22qh79fetq3b7516unb5nhhpe3e8%22,%22redirectUrl%22:%22/invite%22%7D"
      val tokenOpt = OAuth2Helper.getStateToken(tk)
      tokenOpt.isDefined === true
      val stateToken = tokenOpt.get
      stateToken.redirectUrl === Some("/invite")
      stateToken.token === "qh79fetq3b7516unb5nhhpe3e8"
    }

  }
}
