package com.keepit.eliza.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.concurrent.{ FakeExecutionContextModule, WatchableExecutionContext }
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.Id
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.common.time._
import com.keepit.discussion.MessageSource
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.BasicKeepEvent.BasicKeepEventId.MessageId
import com.keepit.model._
import com.keepit.rover.FakeRoverServiceModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ ElizaInjectionHelpers, ElizaTestInjector }
import org.specs2.mutable.SpecificationLike

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class NotificationDeliveryCommanderTest extends TestKitSupport with SpecificationLike with ElizaTestInjector with ElizaInjectionHelpers {
  implicit def time: CrossServiceTime = CrossServiceTime(currentDateTime)
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

  "NotificationDeliveryCommander" should {
    "construct the correct notification for message threads" in {
      "for a single user sending a single message" in {
        withDb(modules: _*) { implicit injector =>
          val user1 = Id[User](1)
          val user2 = Id[User](2)
          val (thread, msg) = Await.result(messagingCommander.sendNewMessage(
            user1,
            Seq(user1, user2),
            Seq.empty,
            url = "http://idgaf.com",
            titleOpt = None,
            messageText = "I need this to work",
            source = Some(MessageSource.CHROME)
          ), Duration.Inf)
          inject[WatchableExecutionContext].drain()

          val notif = Await.result(inject[MessageThreadNotificationBuilder].buildForKeep(user1, thread.keepId), Duration.Inf).get
          notif.id === Some(MessageId(msg.pubId))
          notif.time === msg.createdAt
          notif.threadId === thread.pubKeepId
          notif.text === "I need this to work"
          notif.url === "http://idgaf.com"
          notif.title must beNone
          notif.category === NotificationCategory.User.MESSAGE
          notif.unread === false
          notif.muted === false
          notif.numMessages === 1
          notif.numUnreadMessages === 0
        }
      }
      "for a hilarious sequence of back-and-forths" in {
        withDb(modules: _*) { implicit injector =>
          val user1 = Id[User](1)
          val users = (1 to 5).map(Id[User](_)).toList
          val (initThread, _) = Await.result(messagingCommander.sendNewMessage(
            user1,
            users,
            Seq.empty,
            url = "http://idgaf.com",
            titleOpt = None,
            messageText = "Yo, fake users, let's ruin Ryan's life.",
            source = Some(MessageSource.CHROME)
          ), Duration.Inf)

          val threadId = initThread.id.get

          val sources = List(MessageSource.CHROME, MessageSource.SITE, MessageSource.FIREFOX, MessageSource.IPHONE, MessageSource.ANDROID)
          val uniqueTokens = List("asdf1nakjsdfh", "15ulskdnqrst", "tn1051d1uasn", "123jlaksjd", "1oiualksdn1oi")
          val notifs = for ((sender, source, token) <- (users, sources, uniqueTokens).zipped.toList) yield {
            val currentThread = db.readOnlyMaster { implicit session => messageThreadRepo.get(threadId) }
            val (thread, msg) = messagingCommander.sendMessage(sender, currentThread, s"Ruining Ryan's life! Yeah! $token", source = Some(source), urlOpt = None)
            inject[WatchableExecutionContext].drain()
            Await.result(inject[MessageThreadNotificationBuilder].buildForKeep(user1, thread.keepId), Duration.Inf).get
          }

          val msg = db.readOnlyMaster { implicit s => messageRepo.aTonOfRecords.last }
          val notif = notifs.last
          notif.id === Some(MessageId(msg.pubId))
          notif.time === msg.createdAt
          notif.threadId === initThread.pubKeepId
          notif.text === s"Ruining Ryan's life! Yeah! ${uniqueTokens.last}"
          notif.url === "http://idgaf.com"
          notif.title must beNone
          notif.category === NotificationCategory.User.MESSAGE
          notif.unread === true
          notif.muted === false
          notif.numMessages === 6
          notif.numUnreadMessages === 4
        }
      }
    }
  }
}
