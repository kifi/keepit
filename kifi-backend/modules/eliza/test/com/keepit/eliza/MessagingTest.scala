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

import play.api.test.Helpers._
import play.api.libs.json.{Json}
import com.keepit.realtime.UrbanAirship


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
    val urbanAirship:UrbanAirship = null
    val messagingController = new MessagingController(threadRepo, userThreadRepo, messageRepo, shoebox, db, notificationRouter, clock, uriNormalizationUpdater, urbanAirship)

    val user1 = Id[User](42)
    val user2 = Id[User](43)
    val user3 = Id[User](44)
    val user2n3Set = Set[Id[User]](user2, user3)

    (messagingController, user1, user2, user3, user2n3Set, notificationRouter)
  }

  "Messaging Contoller" should {

    "send correctly" in {
      withDb(ElizaCacheModule(), FakeShoeboxServiceModule(), TestElizaServiceClientModule(), StandaloneTestActorSystemModule()) { implicit injector =>

        val (messagingController, user1, user2, user3, user2n3Set, notificationRouter) = setup()

        val msg1 = messagingController.sendNewMessage(user1, user2n3Set, Json.obj("url" -> "http://thenextgoogle.com"), Some("title"), "World!")
        val msg2 = messagingController.sendMessage(user1, msg1.thread, "Domination!", None)

        val messageIds : Seq[Option[Id[Message]]] = messagingController.getThreads(user2).flatMap(messagingController.getThreadMessages(_, None)).map(_.id)
        val messageContents : Seq[String] = messagingController.getThreads(user2).flatMap(messagingController.getThreadMessages(_, None)).map(_.messageText)

        messageIds.contains(msg1.id)===true
        messageIds.contains(msg2.id)===true

        messageContents.contains(msg1.messageText)===true
        messageContents.contains(msg2.messageText)===true

      }
    }


    "merge and notify correctly" in {
      withDb(ElizaCacheModule(), FakeShoeboxServiceModule(), TestElizaServiceClientModule(), StandaloneTestActorSystemModule()) { implicit injector =>


        val (messagingController, user1, user2, user3, user2n3Set, notificationRouter) = setup()

        var notified = scala.collection.concurrent.TrieMap[Id[User], Int]()  

        notificationRouter.onNotification{ (userId, notification) =>
          // println(s"Got Notification $notification for $userId")
          if (notified.isDefinedAt(userId.get)) {
            notified(userId.get) = notified(userId.get) + 1 
          } else {
            notified(userId.get) = 1
          }
        }

        val msg1 = messagingController.sendNewMessage(user1, user2n3Set, Json.obj("url" -> "http://kifi.com"), Some("title"), "Hello Chat")
        val msg2 = messagingController.sendNewMessage(user1, user2n3Set, Json.obj("url" -> "http://kifi.com"), Some("title"), "Hello Chat again!")
        
        
        notified.isDefinedAt(user1)===false
        notified(user2)===2

        messagingController.getLatestSendableNotifications(user3, 10)

        messagingController.getPendingNotifications(user3).length===1 //there was only one thread created due to merging
        messagingController.setAllNotificationsRead(user3)
        messagingController.getPendingNotifications(user3).length===0

      }
    }


  }

}
