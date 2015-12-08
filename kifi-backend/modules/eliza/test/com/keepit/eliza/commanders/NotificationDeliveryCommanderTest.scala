package com.keepit.eliza.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.json.TestHelper
import com.keepit.common.concurrent.{ WatchableExecutionContext, FakeExecutionContextModule }
import com.keepit.common.crypto.{ PublicIdConfiguration, FakeCryptoModule }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.common.time._
import com.keepit.eliza.model.{ MessageThread, MessageSource, MessageSender, ElizaMessage }
import com.keepit.discussion.Message
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.{ Keep, MessageFactory, MessageThreadFactory, _ }
import com.keepit.rover.FakeRoverServiceModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ ElizaInjectionHelpers, ElizaTestInjector }
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{ JsObject, JsNull, Json }

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

  def zip3[A, B, C](xs: List[A], ys: List[B], zs: List[C]): List[(A, B, C)] = (xs, ys, zs) match {
    case (Nil, _, _) => Nil
    case (_, Nil, _) => Nil
    case (_, _, Nil) => Nil
    case (x :: xx, y :: yy, z :: zz) => (x, y, z) :: zip3(xx, yy, zz)
  }
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

          val notif = Await.result(notificationDeliveryCommander.buildNotificationForMessageThread(user1, thread), Duration.Inf).get
          Set(
            "id", "time", "thread", "text", "url",
            "title", "author", "participants",
            "locator", "unread", "category",
            "messages", "muted", "unreadMessages"
          ) subsetOf notif.as[JsObject].keys

          (notif \ "id").as[ExternalId[Message]] === msg.externalId
          (notif \ "time").as[DateTime] === msg.createdAt
          (notif \ "thread").as[ExternalId[MessageThread]] === thread.externalId
          (notif \ "text").as[String] === "I need this to work"
          (notif \ "url").as[String] === "http://idgaf.com"
          (notif \ "title") === JsNull
          (notif \ "unread").as[Boolean] === false
          (notif \ "category").as[String] === "message"
          (notif \ "messages").as[Int] === 1
          (notif \ "muted").as[Boolean] === false
          (notif \ "unreadMessages").as[Int] === 0
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
          val notifs = for ((sender, source, token) <- zip3(users, sources, uniqueTokens)) yield {
            val (thread, msg) = messagingCommander.sendMessage(sender, threadId, s"Ruining Ryan's life! Yeah! $token", source = Some(source), urlOpt = None)
            inject[WatchableExecutionContext].drain()

            Await.result(notificationDeliveryCommander.buildNotificationForMessageThread(user1, thread), Duration.Inf).get
          }

          val msg = db.readOnlyMaster { implicit s => messageRepo.all.last }
          val notif = notifs.last
          Set(
            "id", "time", "thread", "text", "url",
            "title", "author", "participants",
            "locator", "unread", "category",
            "messages", "muted", "unreadMessages"
          ) subsetOf notif.as[JsObject].keys

          (notif \ "id").as[ExternalId[Message]] === msg.externalId
          (notif \ "time").as[DateTime] === msg.createdAt
          (notif \ "thread").as[ExternalId[MessageThread]] === initThread.externalId
          (notif \ "text").as[String] === s"Ruining Ryan's life! Yeah! ${uniqueTokens.last}"
          (notif \ "url").as[String] === "http://idgaf.com"
          (notif \ "title") === JsNull
          (notif \ "unread").as[Boolean] === true
          (notif \ "category").as[String] === "message"
          (notif \ "messages").as[Int] === 6
          (notif \ "muted").as[Boolean] === false
          (notif \ "unreadMessages").as[Int] === 4

          1 === 1
        }
      }
    }
  }
}
