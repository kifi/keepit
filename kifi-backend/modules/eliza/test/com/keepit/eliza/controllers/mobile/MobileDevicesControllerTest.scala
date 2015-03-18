package com.keepit.eliza.controllers.mobile

import com.google.inject.Injector
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.controller.{ FakeUserActionsHelper }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ HeimdalContext }
import com.keepit.model.{ Username, User }
import com.keepit.realtime._
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, ShoeboxServiceClient, FakeShoeboxServiceModule }
import com.keepit.test.ElizaTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.{ Json, JsObject }
import play.api.mvc.{ Result, Call }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class MobileDevicesControllerTest extends Specification with ElizaTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    ElizaCacheModule(),
    FakeShoeboxServiceModule(),
    FakeElizaServiceClientModule(),
    FakeActorSystemModule(),
    FakeUrbanAirshipModule(),
    FakeHttpClientModule(),
    FakeElizaStoreModule()
  )

  "Mobile Device Controller" should {

    "register device" in {
      withDb(modules: _*) { implicit injector =>
        val deviceRepo = inject[DeviceRepo]
        val (user1, user2) = db.readWrite { implicit s =>
          val userPika = User(firstName = "Pika", lastName = "Chu", username = Username("pikachu"), normalizedUsername = "pikachu")
          val userJiggly = User(firstName = "Jiggly", lastName = "Puff", username = Username("jigglypuff"), normalizedUsername = "jigglypuff")
          val users = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(userPika, userJiggly)
          val user1 = users(0)
          val user2 = users(1)

          // user2 with existing device (with no signature)
          deviceRepo.save(Device(userId = user2.id.get, token = "token2a", deviceType = DeviceType.IOS))

          // pre-call db checks
          deviceRepo.getByUserId(user1.id.get).isEmpty === true
          deviceRepo.getByUserId(user2.id.get).length === 1

          (user1, user2)
        }

        // start with an empty user, register devices with signatures (new device!)
        val result1ForEmpty = registerDevice(user1, DeviceType.Android, Json.obj("token" -> "token1a", "signature" -> "nexus5"))
        status(result1ForEmpty) must equalTo(OK)
        db.readOnlyMaster { implicit s =>
          val d = deviceRepo.getByUserIdAndDeviceTypeAndSignature(user1.id.get, DeviceType.Android, "nexus5")
          d.get.token === "token1a"
        }

        // change token with same signature (update device!)
        val result2ForEmpty = registerDevice(user1, DeviceType.Android, Json.obj("token" -> "token1b", "signature" -> "nexus5"))
        status(result2ForEmpty) must equalTo(OK)
        db.readOnlyMaster { implicit s =>
          val d = deviceRepo.getByUserIdAndDeviceTypeAndSignature(user1.id.get, DeviceType.Android, "nexus5")
          d.get.token === "token1b"
        }

        // different signature (new device!)
        val result3ForEmpty = registerDevice(user1, DeviceType.Android, Json.obj("token" -> "token1zzzz", "signature" -> "htc-one"))
        status(result3ForEmpty) must equalTo(OK)

        // different device type (new device!)
        val result4ForEmpty = registerDevice(user1, DeviceType.IOS, Json.obj("token" -> "token1xyz", "deviceId" -> "iphone6"))
        status(result4ForEmpty) must equalTo(OK)
        db.readOnlyMaster { implicit s =>
          deviceRepo.getByUserId(user1.id.get).map(d => (d.deviceType, d.signature, d.token)) === Seq(
            (DeviceType.Android, Some("nexus5"), "token1b"),
            (DeviceType.Android, Some("htc-one"), "token1zzzz"),
            (DeviceType.IOS, Some("iphone6"), "token1xyz")
          )
        }

        // start with a user who has existing devices (w/o) signatures, register devices with or without signatures
        // user2 has one ios device (no signature, with token "token2a")

        // register device with no signature provided (also ios)
        val result1ForExisting = registerDevice(user2, DeviceType.IOS, Json.obj("token" -> "token2b"))
        status(result1ForExisting) must equalTo(OK)
        db.readOnlyMaster { implicit s =>
          deviceRepo.getByUserId(user2.id.get).map(d => (d.deviceType, d.signature, d.token)) === Seq(
            (DeviceType.IOS, None, "token2a"),
            (DeviceType.IOS, None, "token2b")
          )
        }

        // register device with signature provided (new device, all others without signatures are deactivated)
        val result2ForExisting = registerDevice(user2, DeviceType.IOS, Json.obj("token" -> "token2wwww", "deviceId" -> "iphone42"))
        status(result2ForExisting) must equalTo(OK)
        db.readOnlyMaster { implicit s =>
          deviceRepo.getByUserId(user2.id.get).map(d => (d.deviceType, d.signature, d.token)) === Seq(
            (DeviceType.IOS, Some("iphone42"), "token2wwww")
          )
        }
      }
    }
  }

  private def registerDevice(user: User, deviceType: DeviceType, body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.registerDevice(deviceType.name)(request(routes.MobileDevicesController.registerDevice(deviceType.name)).withBody(body))
  }
  private def controller(implicit injector: Injector) = inject[MobileDevicesController]
  private def request(route: Call) = FakeRequest(route.method, route.url)
}
