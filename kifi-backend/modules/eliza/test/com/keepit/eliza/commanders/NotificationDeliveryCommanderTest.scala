package com.keepit.eliza.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.json.TestHelper
import com.keepit.common.concurrent.{ WatchableExecutionContext, FakeExecutionContextModule }
import com.keepit.common.crypto.{ PublicIdConfiguration, FakeCryptoModule }
import com.keepit.common.db.Id
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.common.time._
import com.keepit.eliza.model.{ MessageSource, MessageSender, ElizaMessage }
import com.keepit.discussion.Message
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.{ Keep, MessageFactory, MessageThreadFactory, _ }
import com.keepit.rover.FakeRoverServiceModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ ElizaInjectionHelpers, ElizaTestInjector }
import org.apache.commons.lang3.RandomStringUtils
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{ JsNull, Json }

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Random

class NotificationDeliveryCommanderTest extends TestKitSupport with SpecificationLike with ElizaTestInjector with ElizaInjectionHelpers {
  implicit val context = HeimdalContext.empty
  implicit def pubIdConfig(implicit injector: Injector): PublicIdConfiguration = inject[PublicIdConfiguration]
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeShoeboxServiceModule(),
    FakeRoverServiceModule(),
    FakeActorSystemModule(),
    FakeClockModule(),
    FakeExecutionContextModule(),
    ElizaCacheModule(),
    FakeHeimdalServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeCryptoModule(),
    FakeElizaStoreModule()
  )

  "NotificationDeliveryCommander" should {
  }
}
