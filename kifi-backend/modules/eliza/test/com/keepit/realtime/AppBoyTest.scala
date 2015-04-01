package com.keepit.realtime

import com.google.inject.Injector
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.net.{ FakeHttpClientModule }
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.eliza.model.MessageThread
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model._
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, ShoeboxServiceClient, FakeShoeboxServiceModule }
import com.keepit.test.{ ElizaTestInjector, TestInjector }
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import scala.concurrent.{ Await, ExecutionContext }
import scala.concurrent.duration._

class AppBoyTest extends Specification with TestInjector with ElizaTestInjector {

  def modules = Seq(
    ElizaCacheModule(),
    FakeShoeboxServiceModule(),
    FakeElizaServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeActorSystemModule(),
    FakeHttpClientModule(),
    FakeElizaStoreModule(),
    FakeAppBoyModule(),
    FakeExecutionContextModule()
  )

  "AppBoy" should {

    "push simple notification" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, device) = setupData
        appBoyClient.jsons.size === 0

        val notification = SimplePushNotification(unvisitedCount = 3, message = Some("pika"), sound = Some(MobilePushNotifier.DefaultNotificationSound), category = null, experiment = null)
        val notifPushF = appBoy.notifyUser(user1.id.get, Seq(device), notification)
        Await.result(notifPushF, Duration(5, SECONDS))
        appBoyClient.jsons.size === 1
        appBoyClient.jsons.head === Json.parse(
          s"""
             {
                "app_group_id": "${appBoyGroupId}",
                "external_user_ids":["${user1.externalId}"],
                "messages": {
                  "apple_push": {
                    "sound":"notification.aiff",
                    "title":"pika",
                    "alert":"pika",
                    "extra": {
                      "unreadCount":3
                    }
                  }
                }
             }
           """
        )
      }
    }

    "push message thread notification" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, device) = setupData
        appBoyClient.jsons.size === 0

        val notification = MessageThreadPushNotification(id = ExternalId[MessageThread]("5fe6e19f-6092-49f1-b446-5d992fda0034"), unvisitedCount = 3, message = Some("pika"), sound = Some(MobilePushNotifier.DefaultNotificationSound))
        val notifPushF = appBoy.notifyUser(user1.id.get, Seq(device), notification)
        Await.result(notifPushF, Duration(5, SECONDS))
        appBoyClient.jsons.size === 1
        appBoyClient.jsons.head === Json.parse(
          s"""
             {
              "app_group_id":"${appBoyGroupId}",
              "external_user_ids":["${user1.externalId}"],
              "messages":{
                "apple_push":{
                  "sound":"notification.aiff",
                  "title":"pika",
                  "alert":"pika",
                  "extra":{
                    "unreadCount":3,
                    "id":"5fe6e19f-6092-49f1-b446-5d992fda0034"
                  }
                }
              }
            }
           """)
      }
    }

    "push library update notification" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, device) = setupData
        appBoyClient.jsons.size === 0

        val lib1 = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveLibraries(Library(name = "lib1", slug = LibrarySlug("lib1"), ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED, memberCount = 1, keepCount = 0)).head
        val pubLibId1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])

        val notification = LibraryUpdatePushNotification(unvisitedCount = 3, message = Some("pika"), libraryId = lib1.id.get, libraryUrl = Library.formatLibraryPath(user1.username, lib1.slug), sound = Some(MobilePushNotifier.DefaultNotificationSound), category = null, experiment = null)
        val notifPushF = appBoy.notifyUser(user1.id.get, Seq(device), notification)
        Await.result(notifPushF, Duration(5, SECONDS))
        appBoyClient.jsons.size === 1
        appBoyClient.jsons.head === Json.parse(
          s"""
             {
              "app_group_id":"${appBoyGroupId}",
              "external_user_ids":["${user1.externalId}"],
              "messages":{
                "apple_push":{
                  "sound":"notification.aiff",
                  "title":"pika",
                  "alert":"pika",
                  "extra":{
                    "unreadCount":3,
                    "t":"lr",
                    "lid":"${pubLibId1.id}",
                    "lu":"/pikachu/lib1"
                  }
                }
              }
            }
           """)
      }
    }

  }

  private def appBoyGroupId = "4212bbb0-d07b-4109-986a-aac019d8062a"
  private def deviceRepo()(implicit injector: Injector) = inject[DeviceRepo]
  private def appBoy()(implicit injector: Injector) = inject[AppBoyImpl]
  private def appBoyClient()(implicit injector: Injector) = inject[FakeAppBoyClient]

  private def setupData()(implicit injector: Injector) = {
    val userPika = User(firstName = "Pika", lastName = "Chu", username = Username("pikachu"), normalizedUsername = "pikachu")
    val users = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(userPika)
    val user1 = users.head

    val device = db.readWrite { implicit s =>
      deviceRepo.save(Device(userId = user1.id.get, token = None, deviceType = DeviceType.IOS, signature = Some("appboy_iphone6")))
    }
    (user1, device)
  }
}
