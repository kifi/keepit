package com.keepit.eliza.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.Id
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.eliza.{ commanders, FakeElizaServiceClientModule }
import com.keepit.eliza.model.ElizaMessage
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.{ Keep, MessageFactory, MessageThreadFactory }
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ ElizaInjectionHelpers, ElizaTestInjector }
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.Json

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class ElizaDiscussionCommanderTest extends TestKitSupport with SpecificationLike with ElizaTestInjector with ElizaInjectionHelpers {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeShoeboxServiceModule(),
    FakeClockModule(),
    FakeExecutionContextModule(),
    ElizaCacheModule(),
    FakeHeimdalServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeCryptoModule(),
    FakeElizaStoreModule()
  )

  "ElizaDiscussionCommander" should {
    "serve up discussions by keep" in {
      "do the right thing for keeps with and without messages" in {
        withDb(modules: _*) { implicit injector =>
          val keep = Id[Keep](1)
          val keepNoMessages = Id[Keep](2)
          val keepNoThread = Id[Keep](3)

          db.readWrite { implicit s =>
            val thread = MessageThreadFactory.thread().withKeep(keep).saved
            val messages = MessageFactory.messages(10).map(_.withThread(thread).saved)

            MessageThreadFactory.thread().withKeep(keepNoMessages).saved
          }

          val ans = Await.result(discussionCommander.getDiscussionsForKeeps(Set(keep, keepNoMessages, keepNoThread)), Duration.Inf)

          ans(keep).numMessages === 10
          ans(keep).messages must haveLength(discussionCommander.MESSAGES_TO_INCLUDE)

          ans(keepNoMessages).numMessages === 0
          ans(keepNoMessages).messages must haveLength(0)

          ans.get(keepNoThread) must beNone
          1 === 1
        }
      }
    }
  }
}
