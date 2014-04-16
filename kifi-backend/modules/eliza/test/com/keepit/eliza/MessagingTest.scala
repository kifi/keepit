package com.keepit.eliza

import org.specs2.mutable._

import com.keepit.common.db.slick._

import com.keepit.common.db.Id
import com.keepit.inject._
import com.keepit.test.{DbTestInjector}
import com.keepit.shoebox.{ShoeboxServiceClient, FakeShoeboxServiceModule, FakeShoeboxServiceClientImpl}
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

import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.commanders.{MessageFetchingCommander, NotificationCommander, MessagingCommander}
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

    val shoebox = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    shoebox.saveUsers(User(
      id = Some(user1),
      firstName = "Some",
      lastName = "User42"
    ))
    shoebox.saveUsers(User(
      id = Some(user2),
      firstName = "Some",
      lastName = "User43"
    ))
    shoebox.saveUsers(User(
      id = Some(user3),
      firstName = "Some",
      lastName = "User44"
    ))

    (user1, user2, user3, user2n3Seq, shoebox)
  }

  "Messaging Contoller" should {

    "send correctly" in {
      withDb(modules:_*) { implicit injector =>

        val (user1, user2, user3, user2n3Seq, shoebox) = setup()
        val messagingCommander = inject[MessagingCommander]
        var messageFetchingCommanger = inject[MessageFetchingCommander]
        val notificationCommander = inject[NotificationCommander]
        val (thread1, msg1) = messagingCommander.sendNewMessage(user1, user2n3Seq, Nil, Json.obj("url" -> "http://thenextgoogle.com"), Some("title"), "World!")

        val (thread2, msg2) = messagingCommander.sendMessage(user1, msg1.thread, "Domination!", None)

        Await.result(notificationCommander.getLatestSendableNotifications(user1, 20), Duration(4, "seconds")).jsons.length === 1

        val messageIds : Seq[Option[Id[Message]]] = messagingCommander.getThreads(user2).flatMap(messageFetchingCommanger.getThreadMessages(_, None)).map(_.id)
        val messageContents : Seq[String] = messagingCommander.getThreads(user2).flatMap(messageFetchingCommanger.getThreadMessages(_, None)).map(_.messageText)

        messageIds.contains(msg1.id) === true
        messageIds.contains(msg2.id) === true

        messageContents.contains(msg1.messageText) === true
        messageContents.contains(msg2.messageText) === true

      }
    }


    "merge and notify correctly" in {
      withDb(modules:_*) { implicit injector =>

        val (user1, user2, user3, user2n3Seq, shoebox) = setup()
        val messagingCommander = inject[MessagingCommander]
        val notificationCommander = inject[NotificationCommander]
        var notified = scala.collection.concurrent.TrieMap[Id[User], Int]()

        inject[WebSocketRouter].onNotification{ (userId, notification) =>
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

        notificationCommander.getLatestSendableNotifications(user3, 10)

        notificationCommander.getUnreadThreadNotifications(user3).length === 1 //there was only one thread created due to merging
        messagingCommander.getUnreadUnmutedThreadCount(user3) === 1

        val notifications : Seq[JsObject] = Await.result(notificationCommander.getLatestUnreadSendableNotifications(user3, 20), Duration(4, "seconds"))._1.jsons
        notifications.length === 1
        val participants = (notifications.head \ "participants").as[Seq[BasicUser]].sortBy (_.lastName)
        println(participants)
        participants.length === 3
        participants(0).lastName.endsWith(user1.id.toString) === true
        participants(1).lastName.endsWith(user2.id.toString) === true
        participants(2).lastName.endsWith(user3.id.toString) === true

        notificationCommander.setAllNotificationsRead(user3)
        notificationCommander.getUnreadThreadNotifications(user3).length === 0
        messagingCommander.getUnreadUnmutedThreadCount(user3) === 0


      }
    }

    "add participants correctly" in {
      withDb(modules:_*) { implicit injector =>

        val (user1, user2, user3, user2n3Seq, shoebox) = setup()
        val messagingCommander = inject[MessagingCommander]
        val notificationCommander = inject[NotificationCommander]

        val (thread, msg) = messagingCommander.sendNewMessage(user1, Seq(user2), Nil, Json.obj("url" -> "http://kifi.com"), Some("title"), "Fortytwo")

        Thread.sleep(100) //AHHHHHH. Really need to figure out how to test Async code with multiple execution contexts. (https://app.asana.com/0/5674704693855/9223435240746)
        Await.result(notificationCommander.getLatestSendableNotifications(user2, 1), Duration(4, "seconds")).jsons.length === 1
        Await.result(notificationCommander.getLatestSendableNotifications(user3, 1), Duration(4, "seconds")).jsons.length === 0

        val user3ExtId = Await.result(shoebox.getUser(user3), Duration(4, "seconds")).get.externalId
        messagingCommander.addParticipantsToThread(user1, thread.externalId, Seq(user3ExtId))
        Thread.sleep(200) //See comment for same above
        Await.result(notificationCommander.getLatestSendableNotifications(user3, 1), Duration(4, "seconds")).jsons.length === 1
      }
    }

  }

}
