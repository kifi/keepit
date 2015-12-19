package com.keepit.eliza

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.concurrent.{ FakeExecutionContextModule, WatchableExecutionContext }
import com.keepit.common.crypto.{ PublicId, FakeCryptoModule }
import com.keepit.common.db.Id
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.eliza.commanders.{ MessageFetchingCommander, MessagingCommander, NotificationDeliveryCommander }
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model._
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.{ Organization, UserFactory, User }
import com.keepit.realtime.{ FakeAppBoyModule }
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, FakeShoeboxServiceModule, ShoeboxServiceClient }
import com.keepit.social.BasicUser
import com.keepit.test.ElizaTestInjector
import org.specs2.mutable._
import play.api.libs.json.{ JsObject, Json }

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration.Duration

class MessagingTest extends Specification with ElizaTestInjector {

  implicit val context = HeimdalContext.empty

  def modules = {
    Seq(
      FakeExecutionContextModule(),
      ElizaCacheModule(),
      FakeShoeboxServiceModule(),
      FakeHeimdalServiceClientModule(),
      FakeElizaServiceClientModule(),
      FakeActorSystemModule(),
      FakeABookServiceClientModule(),
      FakeAppBoyModule(),
      FakeCryptoModule(),
      FakeElizaStoreModule(),
      FakeRoverServiceClientModule()
    )
  }

  def setup()(implicit injector: Injector) = {
    val user1 = Id[User](42)
    val user2 = Id[User](43)
    val user3 = Id[User](44)
    val user2n3Seq = Seq[Id[User]](user2, user3)

    val shoebox = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    shoebox.saveUsers(
      UserFactory.user().withId(user1).withName("Some", "User42").withUsername("test").get,
      UserFactory.user().withId(user2).withName("Some", "User43").withUsername("test").get,
      UserFactory.user().withId(user3).withName("Some", "User44").withUsername("test").get
    )

    (user1, user2, user3, user2n3Seq, shoebox)
  }

  def waitFor[T](f: => Future[T]) = {
    Await.result(f, Duration(4, "seconds"))
  }

  "Messaging Contoller" should {

    "send correctly" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, user3, user2n3Seq, shoebox) = setup()
        val messagingCommander = inject[MessagingCommander]
        val messageFetchingCommanger = inject[MessageFetchingCommander]
        val notificationCommander = inject[NotificationDeliveryCommander]

        val (thread1, msg1) = waitFor(messagingCommander.sendNewMessage(user1, user2n3Seq, Nil, "http://thenextgoogle.com", Some("title"), "World!", None))
        val (thread2, msg2) = messagingCommander.sendMessage(user1, thread1, "Domination!", None, None)
        inject[WatchableExecutionContext].drain()
        waitFor(notificationCommander.getLatestSendableNotifications(user1, 20, includeUriSummary = false)).length === 1

        val messageIds: Seq[Option[Id[ElizaMessage]]] = messagingCommander.getThreads(user2).flatMap(messageFetchingCommanger.getThreadMessages).map(_.id)
        val messageContents: Seq[String] = messagingCommander.getThreads(user2).flatMap(messageFetchingCommanger.getThreadMessages).map(_.messageText)

        messageIds.contains(msg1.id) === true
        messageIds.contains(msg2.id) === true

        messageContents.contains(msg1.messageText) === true
        messageContents.contains(msg2.messageText) === true

      }
    }

    "merge and notify correctly" in {
      pending("pass reliably in Jenkins") {
        withDb(modules: _*) { implicit injector =>

          val (user1, user2, user3, user2n3Seq, shoebox) = setup()
          val messagingCommander = inject[MessagingCommander]
          val notificationCommander = inject[NotificationDeliveryCommander]
          var notified = scala.collection.concurrent.TrieMap[Id[User], Int]()

          inject[WebSocketRouter].onNotification { (userId, notification) =>
            if (notified.isDefinedAt(userId.get)) {
              notified(userId.get) = notified(userId.get) + 1
            } else {
              notified(userId.get) = 1
            }
          }

          val (thread1, msg1) = waitFor(messagingCommander.sendNewMessage(user1, user2n3Seq, Nil, "http://kifi.com", Some("title"), "Hello Chat", None))
          val (thread2, msg2) = waitFor(messagingCommander.sendNewMessage(user1, user2n3Seq, Nil, "http://kifi.com", Some("title"), "Hello Chat again!", None))

          messagingCommander.getUnreadUnmutedThreadCount(user1) === 0

          notified.isDefinedAt(user1) === false
          notified(user2) === 2

          notificationCommander.getLatestSendableNotifications(user3, 10, includeUriSummary = false)

          notificationCommander.getUnreadThreadNotifications(user3).length === 1 //there was only one thread created due to merging
          messagingCommander.getUnreadUnmutedThreadCount(user3) === 1

          val notifications: Seq[JsObject] = Await.result(notificationCommander.getLatestUnreadSendableNotifications(user3, 20, includeUriSummary = false), Duration(4, "seconds"))._1.map(_.obj)
          notifications.length === 1
          val participants = (notifications.head \ "participants").as[Seq[BasicUser]].sortBy(_.lastName)
          participants.length === 3
          participants(0).lastName.endsWith(user1.id.toString) === true
          participants(1).lastName.endsWith(user2.id.toString) === true
          participants(2).lastName.endsWith(user3.id.toString) === true

          notificationCommander.setAllNotificationsRead(user3)
          notificationCommander.getUnreadThreadNotifications(user3).length === 0
          messagingCommander.getUnreadUnmutedThreadCount(user3) === 0
        }
      }
    }

    "add participants correctly" in {
      withDb(modules: _*) { implicit injector =>

        val (user1, user2, user3, user2n3Seq, shoebox) = setup()
        val messagingCommander = inject[MessagingCommander]
        val notificationCommander = inject[NotificationDeliveryCommander]

        val (thread, msg) = waitFor(messagingCommander.sendNewMessage(user1, Seq(user2), Nil, "http://kifi.com", Some("title"), "Fortytwo", None))

        inject[WatchableExecutionContext].drain()
        waitFor(notificationCommander.getLatestSendableNotifications(user2, 1, includeUriSummary = false)).length === 1
        waitFor(notificationCommander.getLatestSendableNotifications(user3, 1, includeUriSummary = false)).length === 0

        val user3ExtId = Await.result(shoebox.getUser(user3), Duration(4, "seconds")).get.externalId
        messagingCommander.addParticipantsToThread(user1, thread.keepId, Seq(user3ExtId), Seq.empty, Seq.empty)
        inject[WatchableExecutionContext].drain()
        waitFor(notificationCommander.getLatestSendableNotifications(user3, 1, includeUriSummary = false)).length === 1
      }
    }

    "process keepAttribution correctly" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, _, user2n3Seq, _) = setup()
        val userThreadRepo = inject[UserThreadRepo]
        val messagingCommander = inject[MessagingCommander]
        val (thread1, msg1) = waitFor(messagingCommander.sendNewMessage(user1, user2n3Seq, Nil, "https://kifi.com", Some("title"), "Search!", None))

        val user2Threads = db.readOnlyMaster { implicit ro => userThreadRepo.getUserThreads(user2, thread1.uriId) }
        user2Threads.size === 1
        messagingCommander.setLastSeen(user2, user2Threads.head.threadId)

        val otherStarters1 = messagingCommander.keepAttribution(user1, thread1.uriId)
        otherStarters1.isEmpty === true

        val otherStarters2 = messagingCommander.keepAttribution(user2, thread1.uriId)
        otherStarters2.isEmpty === false
        otherStarters2.head === user1
      }
    }
  }

}
