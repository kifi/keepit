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
import com.keepit.eliza.model.MessageSender
import com.keepit.eliza.model._
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.{ Keep, MessageFactory, MessageThreadFactory, _ }
import com.keepit.rover.FakeRoverServiceModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ ElizaInjectionHelpers, ElizaTestInjector }
import org.specs2.mutable.SpecificationLike

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class ElizaDiscussionCommanderTest extends TestKitSupport with SpecificationLike with ElizaTestInjector with ElizaInjectionHelpers {
  implicit val context = HeimdalContext.empty
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

          val ans = discussionCommander.getCrossServiceDiscussionsForKeeps(Set(keep, keepNoMessages, keepNoThread), fromTime = None, maxMessagesShown = 5)

          ans(keep).numMessages === 10
          ans(keep).messages must haveLength(5)

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

          val ans = discussionCommander.getCrossServiceDiscussionsForKeeps(Set(keep), fromTime = None, maxMessagesShown = 5)

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

          Await.result(discussionCommander.sendMessage(user1, "First post!", keep), Duration.Inf)
          db.readOnlyMaster { implicit s =>
            val th = messageThreadRepo.getByKeepId(keep).get
            th.participants.allUsers === Set(user1)
            th.participants.allNonUsers must beEmpty
            userThreadRepo.getByKeep(keep) must haveSize(1)
          }

          Await.result(discussionCommander.sendMessage(user2, "Second post", keep), Duration.Inf)
          db.readOnlyMaster { implicit s =>
            val th = messageThreadRepo.getByKeepId(keep).get
            th.participants.allUsers === Set(user1, user2)
            th.participants.allNonUsers must beEmpty
            userThreadRepo.getByKeep(keep) must haveSize(2)
          }

          val ans = discussionCommander.getCrossServiceDiscussionsForKeeps(Set(keep), fromTime = None, maxMessagesShown = 5).get(keep)
          inject[WatchableExecutionContext].drain()
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

          Await.result(for {
            _ <- discussionCommander.sendMessage(user1, "First post!", keep)
            _ <- discussionCommander.sendMessage(user2, "My first post too!", keep)
            _ <- discussionCommander.sendMessage(user2, "And another post!", keep)
          } yield Unit, Duration.Inf)
          val msgs = db.readOnlyMaster { implicit s => messageRepo.all }

          inject[WatchableExecutionContext].drain()

          db.readOnlyMaster { implicit s =>
            userThreadRepo.getUserThread(user1, keep).map(_.unread) must beSome(true)
            userThreadRepo.getUserThread(user2, keep).map(_.unread) must beSome(false)
          }

          discussionCommander.markAsRead(user1, keep, msgs(1).id.get) must beSome(1)
          db.readOnlyMaster { implicit s =>
            userThreadRepo.getUserThread(user1, keep).map(_.unread) must beSome(true)
          }

          discussionCommander.markAsRead(user1, keep, msgs.last.id.get) must beSome(0)
          db.readOnlyMaster { implicit s =>
            userThreadRepo.getUserThread(user1, keep).map(_.unread) must beSome(false)
          }
          inject[WatchableExecutionContext].drain()
          1 === 1
        }
      }
    }
    "delete threads for keeps" in {
      "work" in {
        val keeps = Set(1, 2, 3).map(Id[Keep](_))
        withDb(modules: _*) { implicit injector =>
          val (mts, uts, msgs) = db.readWrite { implicit s =>
            val mts = keeps.map { kid => MessageThreadFactory.thread().withKeep(kid).saved }
            val uts = mts.flatMap { mt => UserThreadFactory.userThreads(5).map(_.withThread(mt).saved) }
            val msgs = uts.flatMap { ut => MessageFactory.messages(5).map(_.withUserThread(ut).saved) }
            (mts, uts, msgs)
          }
          db.readOnlyMaster { implicit s =>
            mts.foreach { mt => messageThreadRepo.get(mt.id.get).state === MessageThreadStates.ACTIVE }
            uts.foreach { ut => userThreadRepo.get(ut.id.get).state === UserThreadStates.ACTIVE }
            msgs.foreach { msg => messageRepo.get(msg.id.get).state === ElizaMessageStates.ACTIVE }
          }
          db.readWrite { implicit s => discussionCommander.deleteThreadsForKeeps(keeps) }
          db.readOnlyMaster { implicit s =>
            mts.foreach { mt => messageThreadRepo.get(mt.id.get).state === MessageThreadStates.INACTIVE }
            uts.foreach { ut => userThreadRepo.get(ut.id.get).state === UserThreadStates.INACTIVE }
            msgs.foreach { msg => messageRepo.get(msg.id.get).state === ElizaMessageStates.INACTIVE }
          }
          inject[WatchableExecutionContext].drain()
          1 === 1
        }
      }
    }
  }
}
