package com.keepit.bender

import org.specs2.mutable._

import com.keepit.common.db.LargeString._
import com.keepit.common.db.slick._
import com.keepit.inject._
import com.keepit.test.{DbTestInjector}
import play.api.test.Helpers._
import com.google.inject.Injector
import com.keepit.shoebox.{ShoeboxServiceClient, FakeShoeboxServiceModule}

import com.keepit.common.db.{Model, Id, ExternalId}
import com.keepit.model.{User, NormalizedURI}


class MessagingTest extends Specification with DbTestInjector {

  def setup()(implicit injector: Injector) = {
    val threadRepo = inject[MessageThreadRepo]
    val userThreadRepo = inject[UserThreadRepo]
    val messageRepo = inject[MessageRepo]
    val shoebox = inject[ShoeboxServiceClient]
    val db = inject[Database]
    val messagingController = new MessagingController(threadRepo, userThreadRepo, messageRepo, shoebox, db)

    val user1 = Id[User](42)
    val user2 = Id[User](43)
    val user3 = Id[User](44)
    val user2n3Set = Set[Id[User]](user2, user3)

    (messagingController, user1, user2, user3, user2n3Set)
  }

  "Messaging Contoller" should {

    "send correctly" in {
      withDb(FakeShoeboxServiceModule()) { implicit injector =>

        val (messagingController, user1, user2, user3, user2n3Set) = setup()

        val msg1 = messagingController.sendNewMessage(user1, user2n3Set, Some("http://thenextgoogle.com"), "World!")
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
      withDb(FakeShoeboxServiceModule()) { implicit injector =>


        val (messagingController, user1, user2, user3, user2n3Set) = setup()

        var notified = scala.collection.concurrent.TrieMap[Id[User], Int]()  

        messagingController.onNotification{ (userId, notification) =>
          // println(s"Got Notification $notification for $userId")
          if (notified.isDefinedAt(userId.get)) {
            notified(userId.get) = notified(userId.get) + 1 
          } else {
            notified(userId.get) = 1
          }
        }

        val msg1 = messagingController.sendNewMessage(user1, user2n3Set, Some("http://kifi.com"), "Hello Chat")
        val msg2 = messagingController.sendNewMessage(user1, user2n3Set, Some("http://kifi.com"), "Hello Chat again!")
        
        
        notified.isDefinedAt(user1)===false
        notified(user2)===2


        messagingController.getPendingNotifications(user3).length===1 //there was only one thread created due to merging
        messagingController.setAllNotificationsRead(user3)
        messagingController.getPendingNotifications(user3).length===0
      }
    }


  }

}