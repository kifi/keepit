package com.keepit.search.controllers.mobile

import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller.{ FakeUserActionsModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.search.test.SearchTestInjector
import com.keepit.common.util.PlayAppConfigurationModule
import org.specs2.mutable._
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.search.controllers.{ FixedResultIndexModule }

class MobileSearchControllerTest extends SpecificationLike with SearchTestInjector {

  def modules = Seq(
    FakeActorSystemModule(),
    FakeUserActionsModule(),
    FixedResultIndexModule(),
    FakeHttpClientModule(),
    PlayAppConfigurationModule(),
    FakeCryptoModule()
  )

  "MobileSearchController" should {

  }

}

object MobileSearchControllerTest {
}
