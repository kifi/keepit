package com.keepit.eliza

import org.specs2.mutable._

import com.keepit.common.db.LargeString._
import com.keepit.common.db.slick._
import com.keepit.inject._
import com.keepit.test.{DbTestInjector}
import com.google.inject.Injector
import com.keepit.shoebox.{ShoeboxServiceClient, FakeShoeboxServiceModule}
import com.keepit.common.cache.{ElizaCacheModule}
import com.keepit.common.time._
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.common.db.{Model, Id, ExternalId}
import com.keepit.model.{User, NormalizedURI}
import com.keepit.social.BasicUser

import play.api.test.Helpers._
import play.api.libs.json.{Json, JsObject}
import com.keepit.realtime.{UrbanAirship, FakeUrbanAirshipImpl}
import com.keepit.heimdal.FakeHeimdalServiceClientImpl
import com.keepit.common.healthcheck.AirbrakeNotifier


class MessagingTest extends Specification with DbTestInjector {

  def setup()(implicit injector: Injector) = {
    val threadRepo = inject[MessageThreadRepo]
    val userThreadRepo = inject[UserThreadRepo]
    val messageRepo = inject[MessageRepo]
    val shoebox = inject[ShoeboxServiceClient]
    val db = inject[Database]
    val notificationRouter = inject[NotificationRouter]
    val clock = inject[Clock]
    val uriNormalizationUpdater: UriNormalizationUpdater = null
    val urbanAirship: UrbanAirship = new FakeUrbanAirshipImpl()
    val heimdal = new FakeHeimdalServiceClientImpl(inject[AirbrakeNotifier])
    val messagingController = new MessagingController(threadRepo, userThreadRepo, messageRepo, shoebox, db, notificationRouter, clock, uriNormalizationUpdater, urbanAirship, heimdal)

    val user1 = Id[User](42)
    val user2 = Id[User](43)
    val user3 = Id[User](44)
    val user2n3Seq = Seq[Id[User]](user2, user3)

    (messagingController, user1, user2, user3, user2n3Seq, notificationRouter)
  }

  "Messaging Contoller" should {

    "send correctly" in {
      withDb(ElizaCacheModule(), FakeShoeboxServiceModule(), TestElizaServiceClientModule(), StandaloneTestActorSystemModule()) { implicit injector =>

        val (messagingController, user1, user2, user3, user2n3Set, notificationRouter) = setup()

        val (thread1, msg1) = messagingController.sendNewMessage(user1, user2n3Set, Json.obj("url" -> "http://thenextgoogle.com"), Some("title"), "World!")

        messagingController.getLatestSendableNotifications(user1, 20).length === 0

        val (thread2, msg2) = messagingController.sendMessage(user1, msg1.thread, "Domination!", None)

        val messageIds : Seq[Option[Id[Message]]] = messagingController.getThreads(user2).flatMap(messagingController.getThreadMessages(_, None)).map(_.id)
        val messageContents : Seq[String] = messagingController.getThreads(user2).flatMap(messagingController.getThreadMessages(_, None)).map(_.messageText)

        messageIds.contains(msg1.id) === true
        messageIds.contains(msg2.id) === true

        messageContents.contains(msg1.messageText) === true
        messageContents.contains(msg2.messageText) === true

      }
    }


    "merge and notify correctly" in {
      withDb(ElizaCacheModule(), FakeShoeboxServiceModule(), TestElizaServiceClientModule(), StandaloneTestActorSystemModule()) { implicit injector =>

        val (messagingController, user1, user2, user3, user2n3Seq, notificationRouter) = setup()

        var notified = scala.collection.concurrent.TrieMap[Id[User], Int]()

        notificationRouter.onNotification{ (userId, notification) =>
          // println(s"Got Notification $notification for $userId")
          if (notified.isDefinedAt(userId.get)) {
            notified(userId.get) = notified(userId.get) + 1
          } else {
            notified(userId.get) = 1
          }
        }

        val (thread1, msg1) = messagingController.sendNewMessage(user1, user2n3Seq, Json.obj("url" -> "http://kifi.com"), Some("title"), "Hello Chat")
        val (thread2, msg2) = messagingController.sendNewMessage(user1, user2n3Seq, Json.obj("url" -> "http://kifi.com"), Some("title"), "Hello Chat again!")

        messagingController.getUnreadThreadCount(user1) === 0

        notified.isDefinedAt(user1) === false
        notified(user2) === 2

        messagingController.getLatestSendableNotifications(user3, 10)

        messagingController.getUnreadThreadNotifications(user3).length === 1 //there was only one thread created due to merging
        messagingController.getUnreadThreadCount(user3) === 1

        val notifications : Seq[JsObject] = messagingController.getLatestUnreadSendableNotifications(user3, 20)
        notifications.length === 1
        val participants = (notifications.head \ "participants").as[Seq[BasicUser]].sortBy (_.lastName)
        println(participants)
        participants.length === 3
        participants(0).lastName.endsWith(user1.id.toString) === true
        participants(1).lastName.endsWith(user2.id.toString) === true
        participants(2).lastName.endsWith(user3.id.toString) === true

        messagingController.setAllNotificationsRead(user3)
        messagingController.getUnreadThreadNotifications(user3).length === 0
        messagingController.getUnreadThreadCount(user3) === 0


      }
    }


  }

}
