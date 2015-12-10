package com.keepit.eliza.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.concurrent.{ WatchableExecutionContext, FakeExecutionContextModule }
import com.keepit.common.crypto.{ PublicIdConfiguration, FakeCryptoModule }
import com.keepit.common.db.Id
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.common.time._
import com.keepit.eliza.model.{ MessageSender, ElizaMessage }
import com.keepit.discussion.Message
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.{ Keep, MessageFactory, MessageThreadFactory, _ }
import com.keepit.rover.FakeRoverServiceModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ ElizaInjectionHelpers, ElizaTestInjector }
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{ JsNull, Json }

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration.Duration

class ElizaDiscussionCommanderTest extends TestKitSupport with SpecificationLike with ElizaTestInjector with ElizaInjectionHelpers {
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
      "dont' blow up on system messages" in {
        withDb(modules: _*) { implicit injector =>
          val keep = Id[Keep](1)
          db.readWrite { implicit s =>
            val thread = MessageThreadFactory.thread().withKeep(keep).saved
            val userMessage = MessageFactory.message().withThread(thread).saved
            val sysMessage = MessageFactory.message().withThread(thread).from(MessageSender.System).saved
          }

          val ans = Await.result(discussionCommander.getDiscussionsForKeeps(Set(keep)), Duration.Inf)

          ans(keep).numMessages === 1
          ans(keep).messages must haveLength(1)
          db.readOnlyMaster { implicit s => messageRepo.all must haveLength(2) }

          1 === 1
        }
      }
    }
    "add messages to discussions" in {
      "add new commenters as they chime in" in {
        withDb(modules: _*) { implicit injector =>
          val keep = Id[Keep](1)
          val user1 = Id[User](1)
          val user2 = Id[User](2)

          db.readOnlyMaster { implicit s => messageThreadRepo.getByKeepId(keep) must beNone }

          Await.result(discussionCommander.sendMessageOnKeep(user1, "First post!", keep), Duration.Inf)
          db.readOnlyMaster { implicit s =>
            val th = messageThreadRepo.getByKeepId(keep).get
            th.participants.allUsers === Set(user1)
            th.participants.allNonUsers must beEmpty
            userThreadRepo.getByThread(th.id.get) must haveSize(1)
          }

          Await.result(discussionCommander.sendMessageOnKeep(user2, "Second post", keep), Duration.Inf)
          db.readOnlyMaster { implicit s =>
            val th = messageThreadRepo.getByKeepId(keep).get
            th.participants.allUsers === Set(user1, user2)
            th.participants.allNonUsers must beEmpty
            userThreadRepo.getByThread(th.id.get) must haveSize(2)
          }

          val ans = Await.result(discussionCommander.getDiscussionsForKeeps(Set(keep)), Duration.Inf).get(keep)
          ans must beSome
          ans.get.numMessages === 2
        }
      }
    }
    "mark keeps as read" in {
      "handle unread messages" in {
        withDb(modules: _*) { implicit injector =>
          val keep = Id[Keep](1)
          val user1 = Id[User](1)
          val user2 = Id[User](2)

          val msgs = Await.result(Future.sequence(Seq(
            discussionCommander.sendMessageOnKeep(user1, "First post!", keep),
            discussionCommander.sendMessageOnKeep(user2, "My first post too!", keep),
            discussionCommander.sendMessageOnKeep(user2, "And another post!", keep)
          )), Duration.Inf)

          inject[WatchableExecutionContext].drain()

          db.readOnlyMaster { implicit s =>
            val mtId = messageThreadRepo.getByKeepId(keep).get.id.get
            userThreadRepo.getUserThread(user1, mtId).map(_.unread) must beSome(true)
            userThreadRepo.getUserThread(user2, mtId).map(_.unread) must beSome(false)
          }

          discussionCommander.markAsRead(user1, keep, msgs(1).id.get) must beSome(1)
          db.readOnlyMaster { implicit s =>
            val mtId = messageThreadRepo.getByKeepId(keep).get.id.get
            userThreadRepo.getUserThread(user1, mtId).map(_.unread) must beSome(true)
          }

          discussionCommander.markAsRead(user1, keep, msgs.last.id.get) must beSome(0)
          db.readOnlyMaster { implicit s =>
            val mtId = messageThreadRepo.getByKeepId(keep).get.id.get
            userThreadRepo.getUserThread(user1, mtId).map(_.unread) must beSome(false)
          }
          1 === 1
        }
      }
    }
  }
}
