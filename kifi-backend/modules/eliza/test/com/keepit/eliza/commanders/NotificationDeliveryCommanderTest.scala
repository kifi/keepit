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

          val newWay = Await.result(notificationDeliveryCommander.buildNotificationForMessageThread(user1, thread), Duration.Inf)
          val oldWay = db.readOnlyMaster { implicit s => userThreadRepo.getUserThread(user1, thread.id.get).lastNotification }
          TestHelper.deepCompare(newWay, oldWay) must beNone
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

          def randomChoice[T](xs: List[T]): T = xs(Random.nextInt(xs.length))
          for (i <- 1 to 5) {
            val sender = randomChoice(users)
            val source = randomChoice(List(MessageSource.CHROME, MessageSource.SITE, MessageSource.FIREFOX, MessageSource.IPHONE, MessageSource.ANDROID))
            val (thread, msg) = messagingCommander.sendMessage(sender, threadId, s"Ruining Ryan's life! Yeah! ${RandomStringUtils.randomAlphanumeric(30)}", source = Some(source), urlOpt = None)
            inject[WatchableExecutionContext].drain()
            users.foreach { uid =>
              val newWay = Await.result(notificationDeliveryCommander.buildNotificationForMessageThread(uid, thread), Duration.Inf)
              val oldWay = db.readOnlyMaster { implicit s => userThreadRepo.getUserThread(uid, thread.id.get).lastNotification }
              TestHelper.deepCompare(newWay, oldWay) must beNone
            }
          }
          1 === 1
        }
      }
    }
  }
}
