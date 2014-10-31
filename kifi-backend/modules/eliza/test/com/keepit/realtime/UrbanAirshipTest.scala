package com.keepit.realtime

import com.google.inject.Provides
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.eliza.model.MessageThread
import com.keepit.model.User
import com.keepit.test.{ ElizaTestInjector, TestInjector }
import net.codingwell.scalaguice.ScalaModule
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class UrbanAirshipTest extends Specification with TestInjector with ElizaTestInjector {

  "Urban Airship" should {

    "create ios json" in {
      withInjector() { implicit injector =>

        val urbanAirship = inject[UrbanAirship]
        val urbanAirshipClient = inject[FakeUrbanAirshipClient]
        urbanAirshipClient.jsons.size === 0
        val device = Device(userId = Id[User](1), token = "32e0a1c0cd0860ea392d06d26bbd1d4f289bbc488c29d634aee8ccb10e812f63", deviceType = DeviceType.IOS)
        val notification = PushNotification(id = ExternalId[MessageThread]("5fe6e19f-6092-49f1-b446-5d992fda0034"), unvisitedCount = 3, message = Some("bar"), sound = Some(UrbanAirship.DefaultNotificationSound))
        urbanAirship.sendNotification(device, notification)
        urbanAirshipClient.jsons.size === 1
        urbanAirshipClient.jsons(0) === Json.parse(
          """
            {
            "audience":
              {"device_token":"32e0a1c0cd0860ea392d06d26bbd1d4f289bbc488c29d634aee8ccb10e812f63"},
            "device_types":
              ["ios"],
            "notification":
              {
                "ios":
                  {
                    "alert":"bar - ios","badge":3,"sound":"notification.aiff","content-available":true,"extra":{"unreadCount":3,"id":"5fe6e19f-6092-49f1-b446-5d992fda0034"}
                  }
              }
            }
          """)
      }
    }
  }

  "create android json" in {
    withInjector() { implicit injector =>

      val urbanAirship = inject[UrbanAirship]
      val urbanAirshipClient = inject[FakeUrbanAirshipClient]
      urbanAirshipClient.jsons.size === 0
      val device = Device(userId = Id[User](1), token = "44cae599-f7c2-41f0-83a6-7843c5416770", deviceType = DeviceType.Android)
      val notification = PushNotification(id = ExternalId[MessageThread]("5fe6e19f-6092-49f1-b446-5d992fda0034"), unvisitedCount = 3, message = Some("bar"), sound = Some(NotificationSound("sound.mp3")))
      urbanAirship.sendNotification(device, notification)
      urbanAirshipClient.jsons.size === 1
      urbanAirshipClient.jsons(0) === Json.parse(
        """
          {
            "audience":{"apid":"8c265c51-16a8-4559-8b2e-d8b46f62bf06"},
            "device_types":["android"],
            "notification":{
              "android":{"alert":"bar - android","extra":{"unreadCount":"3","id":"5fe6e19f-6092-49f1-b446-5d992fda0034"}}
            }
          }
        """)
    }
  }

}