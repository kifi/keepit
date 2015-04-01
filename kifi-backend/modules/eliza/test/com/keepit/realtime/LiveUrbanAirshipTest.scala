package com.keepit.realtime

import com.google.inject.Provides
import com.keepit.common.db._
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.eliza.model.MessageThread
import com.keepit.model.User
import com.keepit.test.{ ElizaApplication, ElizaApplicationInjector }
import net.codingwell.scalaguice.ScalaModule
import org.specs2.mutable.Specification
import play.api.test.Helpers._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class LiveUrbanAirshipTest extends Specification with ElizaApplicationInjector {

  "Urban Airship" should {
    args(skipAll = true)
    "send live ios message" in {
      running(new ElizaApplication(ProdHttpClientModule(), new ScalaModule {
        def configure: Unit = {}

        @Provides
        def config: UrbanAirshipConfig = {
          val key = "PCP1hTNTSA6YwXSjT1cSbA"
          val secret = "3iQ62IA8Te6m9vXxyLe3Yw"
          val dev_key = "3k0ibINMQtOwweZARPe4iw"
          val dev_secret = "cY3VB2IqS1Wkhq8PWXni0w"
          UrbanAirshipConfig(key = key, secret = secret, devKey = dev_key, devSecret = dev_secret)
        }
      })) {
        val urbanAirship = inject[UrbanAirshipImpl]
        val notification = MessageThreadPushNotification(id = ExternalId[MessageThread](), unvisitedCount = 44, message = Some("Hello ios :-)"), sound = Some(MobilePushNotifier.DefaultNotificationSound))
        val device = Device(userId = Id[User](1), token = Some("cf09d7db6968c11472b65a7050413180fbf98118c52be93aef9e79095bbe73cf"), deviceType = DeviceType.IOS, isDev = false)
        val json = urbanAirship.createIosJson(notification, device)
        val client = inject[DevAndProdUrbanAirshipClient]
        val res = Await.result(client.send(json, device, notification), Duration.Inf)
        println(s"res from the ship: $res")
        1 === 1
      }
    }

    "send live android message" in {
      running(new ElizaApplication(ProdHttpClientModule(), new ScalaModule {
        def configure: Unit = {}

        @Provides
        def config: UrbanAirshipConfig = {
          val key = "PCP1hTNTSA6YwXSjT1cSbA"
          val secret = "3iQ62IA8Te6m9vXxyLe3Yw"
          val dev_key = "3k0ibINMQtOwweZARPe4iw"
          val dev_secret = "cY3VB2IqS1Wkhq8PWXni0w"
          UrbanAirshipConfig(key = key, secret = secret, devKey = dev_key, devSecret = dev_secret)
        }
      })) {
        val urbanAirship = inject[UrbanAirshipImpl]
        val notification = MessageThreadPushNotification(id = ExternalId[MessageThread](), unvisitedCount = 44, message = Some("Hello android:-)"), sound = Some(MobilePushNotifier.DefaultNotificationSound))
        val device = Device(userId = Id[User](1), token = Some("64edb4bf-ed9c-4139-8d16-1e338558032a"), deviceType = DeviceType.Android, isDev = false)
        val json = urbanAirship.createAndroidJson(notification, device)
        val client = inject[DevAndProdUrbanAirshipClient]
        val res = Await.result(client.send(json, device, notification), Duration.Inf)
        println(s"res from the ship: $res")
        1 === 1
      }
    }
  }
}