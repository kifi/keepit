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
        val (user1, deviceApple, deviceAndroid) = setupData
        appBoyClient.jsons.size === 0

        val notification = SimplePushNotification(unvisitedCount = 3, message = Some("pika"), sound = Some(MobilePushNotifier.DefaultNotificationSound), category = null, experiment = null)
        val notifPushF = appBoy.notifyUser(user1.id.get, Seq(deviceApple, deviceAndroid), notification, false)
        Await.result(notifPushF, Duration(5, SECONDS))
        appBoyClient.jsons.size === 1

        // test both platforms push notification
        appBoyClient.jsons(0) === Json.parse(
          s"""
             |{
             |  "app_group_id": "$appBoyGroupId",
             |  "external_user_ids": [
             |    "${user1.externalId.id}"
             |  ],
             |  "campaign_id": "2c22f953-902a-4f3c-88f0-34fe07edeccf",
             |  "messages": {
             |    "apple_push": {
             |      "message_variation_id": "iosPush-9",
             |      "badge": 3,
             |      "sound": "notification.aiff",
             |      "alert": "pika",
             |      "content-available":false,
             |      "extra": {
             |        "unreadCount": 3
             |      }
             |    },
             |    "android_push": {
             |      "message_variation_id": "androidPush-12",
             |      "badge": 3,
             |      "sound": "notification.aiff",
             |      "alert": "pika",
             |      "content-available":false,
             |      "extra": {
             |        "unreadCount": 3
             |      },
             |      "title": "pika"
             |    }
             |  }
             |}
          """.stripMargin
        )
      }
    }

    "push message thread notification" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, deviceApple, deviceAndroid) = setupData
        appBoyClient.jsons.size === 0

        val notification = MessageThreadPushNotification(id = ExternalId[MessageThread]("5fe6e19f-6092-49f1-b446-5d992fda0034"), unvisitedCount = 3, message = Some("pika"), sound = Some(MobilePushNotifier.DefaultNotificationSound))
        val notifPushF = appBoy.notifyUser(user1.id.get, Seq(deviceApple, deviceAndroid), notification, false)
        Await.result(notifPushF, Duration(5, SECONDS))
        appBoyClient.jsons.size === 1

        // test apple push notification
        appBoyClient.jsons(0) === Json.parse(
          s"""
             {
              "app_group_id":"${appBoyGroupId}",
              "external_user_ids":["${user1.externalId}"],
              "campaign_id": "2c22f953-902a-4f3c-88f0-34fe07edeccf",
              "messages":{
                "apple_push":{
                  "message_variation_id": "iosPush-9",
                  "badge": 3,
                  "sound":"notification.aiff",
                  "alert":"pika",
                  "content-available":false,
                  "extra":{
                    "unreadCount":3,
                    "id":"5fe6e19f-6092-49f1-b446-5d992fda0034"
                  }
                },
                "android_push":{
                  "message_variation_id": "androidPush-12",
                  "badge": 3,
                  "sound":"notification.aiff",
                  "alert":"pika",
                  "content-available":false,
                  "extra":{
                    "unreadCount":3,
                    "id":"5fe6e19f-6092-49f1-b446-5d992fda0034"
                  },
                  "title":"pika"
                }
              }
            }
           """)
      }
    }

    "push library update notification" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, deviceApple, deviceAndroid) = setupData
        appBoyClient.jsons.size === 0

        val lib1 = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveLibraries(Library(name = "lib1", slug = LibrarySlug("lib1"), ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED, memberCount = 1, keepCount = 0)).head
        val pubLibId1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])

        val notification = LibraryUpdatePushNotification(unvisitedCount = 3, message = Some("pika"), libraryId = lib1.id.get, libraryUrl = Library.formatLibraryPath(user1.username, lib1.slug), sound = Some(MobilePushNotifier.DefaultNotificationSound), category = null, experiment = null)
        val notifPushF = appBoy.notifyUser(user1.id.get, Seq(deviceApple, deviceAndroid), notification, false)
        Await.result(notifPushF, Duration(5, SECONDS))
        appBoyClient.jsons.size === 1

        // test apple push notification
        appBoyClient.jsons(0) === Json.parse(
          s"""
             {
              "app_group_id":"${appBoyGroupId}",
              "external_user_ids":["${user1.externalId}"],
              "campaign_id": "2c22f953-902a-4f3c-88f0-34fe07edeccf",
              "messages":{
                "apple_push":{
                  "message_variation_id": "iosPush-9",
                  "badge": 3,
                  "sound":"notification.aiff",
                  "alert":"pika",
                  "content-available":false,
                  "extra":{
                    "unreadCount":3,
                    "t":"lr",
                    "lid":"${pubLibId1.id}",
                    "lu":"/pikachu/lib1"
                  }
                },
                "android_push":{
                  "message_variation_id": "androidPush-12",
                  "badge": 3,
                  "sound":"notification.aiff",
                  "alert":"pika",
                  "content-available":false,
                  "extra":{
                    "unreadCount":3,
                    "t":"lr",
                    "lid":"${pubLibId1.id}"
                  },
                  "title":"pika"
                }
              }
            }
           """)
      }
    }

    "push user notification" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, deviceApple, deviceAndroid) = setupData
        appBoyClient.jsons.size === 0

        val notification = UserPushNotification(unvisitedCount = 3, message = Some("pika"), username = Username("joe"), userExtId = user1.externalId, pictureUrl = "http://www.asdf.com/asdfasdf", sound = None, category = null, experiment = null)
        val notifPushF = appBoy.notifyUser(user1.id.get, Seq(deviceApple, deviceAndroid), notification, false)
        Await.result(notifPushF, Duration(5, SECONDS))
        appBoyClient.jsons.size === 1

        // test apple push notification
        appBoyClient.jsons(0) === Json.parse(
          s"""
             {
              "app_group_id":"${appBoyGroupId}",
              "external_user_ids":["${user1.externalId}"],
              "campaign_id": "2c22f953-902a-4f3c-88f0-34fe07edeccf",
              "messages":{
                "apple_push":{
                  "message_variation_id": "iosPush-9",
                  "badge": 3,
                  "sound":null,
                  "alert":"pika",
                  "content-available":false,
                  "extra":{
                    "unreadCount":3,
                    "t":"us",
                    "uid":"${user1.externalId}",
                    "un":"joe",
                    "purl":"http://www.asdf.com/asdfasdf"
                  }
                },
                "android_push":{
                  "message_variation_id": "androidPush-12",
                  "badge": 3,
                  "sound":null,
                  "alert":"pika",
                  "content-available":false,
                  "extra":{
                    "unreadCount":3,
                    "t":"us",
                    "uid":"${user1.externalId}",
                    "un":"joe",
                    "purl":"http://www.asdf.com/asdfasdf"
                  },
                  "title":"pika"
                }
              }
            }
           """)
      }
    }

  }

  private def appBoyGroupId = AppBoyConfig.appGroupId
  private def deviceRepo()(implicit injector: Injector) = inject[DeviceRepo]
  private def appBoy()(implicit injector: Injector) = inject[AppBoy]
  private def appBoyClient()(implicit injector: Injector) = inject[FakeAppBoyClient]

  private def setupData()(implicit injector: Injector) = {
    val userPika = User(firstName = "Pika", lastName = "Chu", username = Username("pikachu"), normalizedUsername = "pikachu")
    val users = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(userPika)
    val user1 = users.head

    val (deviceApple, deviceAndroid) = db.readWrite { implicit s =>
      val deviceApple = deviceRepo.save(Device(userId = user1.id.get, token = None, deviceType = DeviceType.IOS, signature = Some("appboy_iphone6")))
      val deviceAndroid = deviceRepo.save(Device(userId = user1.id.get, token = None, deviceType = DeviceType.Android, signature = Some("appboy_galaxy6")))
      (deviceApple, deviceAndroid)
    }
    (user1, deviceApple, deviceAndroid)
  }
}
