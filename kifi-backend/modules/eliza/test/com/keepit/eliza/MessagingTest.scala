package com.keepit.eliza

import org.specs2.mutable._

import com.keepit.common.db.slick._

import com.keepit.common.db.Id
import com.keepit.inject._
import com.keepit.test.{DbTestInjector}
import com.keepit.shoebox.{ShoeboxServiceClient, FakeShoeboxServiceModule}
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.time._
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.social.BasicUser
import com.keepit.realtime.{UrbanAirship, FakeUrbanAirship, FakeUrbanAirshipModule}
import com.keepit.heimdal.{HeimdalContext, TestHeimdalServiceClientModule}
import com.keepit.common.healthcheck.FakeAirbrakeNotifier
import com.keepit.abook.{FakeABookServiceClientImpl, ABookServiceClient, TestABookServiceClientModule}

import com.keepit.eliza.controllers.NotificationRouter
import com.keepit.eliza.commanders.{MessagingCommander, MessagingIndexCommander}
import com.keepit.eliza.controllers.internal.MessagingController
import com.keepit.eliza.model._

import com.google.inject.Injector

import play.api.test.Helpers._
import play.api.libs.json.{Json, JsObject}

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import akka.actor.ActorSystem


class MessagingTest extends Specification with DbTestInjector {

  implicit val context = HeimdalContext.empty

  def modules = {
    implicit val system = ActorSystem("test")
    Seq(
      ElizaCacheModule(),
      FakeShoeboxServiceModule(),
      TestHeimdalServiceClientModule(),
      FakeElizaServiceClientModule(),
      StandaloneTestActorSystemModule(),
      TestABookServiceClientModule(),
      FakeUrbanAirshipModule()
    )
  }

  def setup()(implicit injector: Injector) = {
    val user1 = Id[User](42)
    val user2 = Id[User](43)
    val user3 = Id[User](44)
    val user2n3Seq = Seq[Id[User]](user2, user3)

    (user1, user2, user3, user2n3Seq)
  }

  "Messaging Contoller" should {

    "send correctly" in {
      withDb(modules:_*) { implicit injector =>

        val (user1, user2, user3, user2n3Seq) = setup()
        val messagingCommander = inject[MessagingCommander]
        val (thread1, msg1) = messagingCommander.sendNewMessage(user1, user2n3Seq, Nil, Json.obj("url" -> "http://thenextgoogle.com"), Some("title"), "World!")

        Await.result(messagingCommander.getLatestSendableNotificationsNotJustFromMe(user1, 20), Duration(4, "seconds")).length === 0

        val (thread2, msg2) = messagingCommander.sendMessage(user1, msg1.thread, "Domination!", None)

        val messageIds : Seq[Option[Id[Message]]] = messagingCommander.getThreads(user2).flatMap(messagingCommander.getThreadMessages(_, None)).map(_.id)
        val messageContents : Seq[String] = messagingCommander.getThreads(user2).flatMap(messagingCommander.getThreadMessages(_, None)).map(_.messageText)

        messageIds.contains(msg1.id) === true
        messageIds.contains(msg2.id) === true

        messageContents.contains(msg1.messageText) === true
        messageContents.contains(msg2.messageText) === true

      }
    }


    "merge and notify correctly" in {
      withDb(modules:_*) { implicit injector =>

        val (user1, user2, user3, user2n3Seq) = setup()
        val messagingCommander = inject[MessagingCommander]
        var notified = scala.collection.concurrent.TrieMap[Id[User], Int]()

        inject[NotificationRouter].onNotification{ (userId, notification) =>
          // println(s"Got Notification $notification for $userId")
          if (notified.isDefinedAt(userId.get)) {
            notified(userId.get) = notified(userId.get) + 1
          } else {
            notified(userId.get) = 1
          }
        }

        val (thread1, msg1) = messagingCommander.sendNewMessage(user1, user2n3Seq, Nil, Json.obj("url" -> "http://kifi.com"), Some("title"), "Hello Chat")
        val (thread2, msg2) = messagingCommander.sendNewMessage(user1, user2n3Seq, Nil, Json.obj("url" -> "http://kifi.com"), Some("title"), "Hello Chat again!")

        messagingCommander.getUnreadUnmutedThreadCount(user1) === 0

        notified.isDefinedAt(user1) === false
        notified(user2) === 2

        messagingCommander.getLatestSendableNotifications(user3, 10)

        messagingCommander.getUnreadThreadNotifications(user3).length === 1 //there was only one thread created due to merging
        messagingCommander.getUnreadUnmutedThreadCount(user3) === 1

        val notifications : Seq[JsObject] = Await.result(messagingCommander.getLatestUnreadSendableNotifications(user3, 20), Duration(4, "seconds"))
        notifications.length === 1
        val participants = (notifications.head \ "participants").as[Seq[BasicUser]].sortBy (_.lastName)
        println(participants)
        participants.length === 3
        participants(0).lastName.endsWith(user1.id.toString) === true
        participants(1).lastName.endsWith(user2.id.toString) === true
        participants(2).lastName.endsWith(user3.id.toString) === true

        messagingCommander.setAllNotificationsRead(user3)
        messagingCommander.getUnreadThreadNotifications(user3).length === 0
        messagingCommander.getUnreadUnmutedThreadCount(user3) === 0


      }
    }


  }

}
