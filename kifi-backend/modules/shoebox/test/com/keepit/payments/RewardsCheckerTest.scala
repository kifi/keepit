package com.keepit.payments

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.Id
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{ JsSuccess, Json }

class RewardsCheckerTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  def modules = Seq(
    FakeExecutionContextModule(),
    FakeSearchServiceClientModule(),
    FakeMailModule(),
    FakeShoeboxStoreModule(),
    FakeCryptoModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeHeimdalServiceClientModule()
  )

  "RewardsChecker" should {
    "backfill checklist rewards" in {
      "backfill rewards to an org" in {
        skipped("do this")
        withDb(modules: _*) { implicit injector =>
          1 === 1
        }
      }
      "avoid blocked rewards" in {
        skipped("do this")
        withDb(modules: _*) { implicit injector =>
          1 === 1
        }
      }
    }
  }
}
