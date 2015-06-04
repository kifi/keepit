package com.keepit.eliza.controllers.mobile

import com.google.inject.Injector
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.{ FakeUserActionsHelper }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.{ UserFactory, User }
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
    FakeHeimdalServiceClientModule(),
    FakeActorSystemModule(),
    FakeUrbanAirshipModule(),
    FakeAppBoyModule(),
    FakeHttpClientModule(),
    FakeElizaStoreModule(),
    FakeExecutionContextModule()
  )

  def initUsers()(implicit injector: Injector): Seq[User] = {
    val pika = UserFactory.user().withId(1).withName("Pika", "Chu").withUsername("pikachu").get
    val jiggly = UserFactory.user().withId(2).withName("Jiggly", "Puff").withUsername("jigglypuff").get
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(pika, jiggly)
  }

  "Mobile Device Controller" should {

    "register device (without a token)" in {
      withDb(modules: _*) { implicit injector =>
        val deviceRepo = inject[DeviceRepo]
        val (user1, user2) = db.readWrite { implicit s =>
          deviceRepo.count === 0
          val Seq(user1, user2) = initUsers()

          // user2 with existing device (with no signature and a token)
          deviceRepo.save(Device(userId = user2.id.get, token = Some("token2a"), deviceType = DeviceType.IOS))
          // user2 with existing device (with a signature and no token)
          deviceRepo.save(Device(userId = user2.id.get, token = None, deviceType = DeviceType.Android, signature = Some("galaxynote")))

          // pre-call db checks
          deviceRepo.getByUserId(user1.id.get).isEmpty === true
          deviceRepo.getByUserId(user2.id.get).length === 2

          (user1, user2)
        }

        // start with an empty user, register devices with signatures (new device!)
        val result1ForEmpty = registerDevice(user1, DeviceType.Android, Json.obj("signature" -> "nexus5"))
        status(result1ForEmpty) must equalTo(OK)
        db.readOnlyMaster { implicit s =>
          deviceRepo.getByUserIdAndDeviceTypeAndSignature(user1.id.get, DeviceType.Android, "ab_nexus5").isDefined === true
          deviceRepo.getByUserId(user1.id.get).length === 1
        }

        // updated device for legacy user
        val result2ForEmpty = registerDevice(user2, DeviceType.Android, Json.obj("signature" -> "galaxynote"))
        status(result2ForEmpty) must equalTo(OK)
        db.readOnlyMaster { implicit s =>
          deviceRepo.getByUserIdAndDeviceTypeAndSignature(user2.id.get, DeviceType.Android, "ab_galaxynote").isDefined === true
          deviceRepo.getByUserIdAndDeviceTypeAndSignature(user2.id.get, DeviceType.Android, "galaxynote").isDefined === false
          deviceRepo.getByUserId(user2.id.get).length === 2 // one with token, one for ab_galaxynote
        }

        // new device for legacy user
        val result3ForEmpty = registerDevice(user2, DeviceType.IOS, Json.obj("signature" -> "iphone6"))
        status(result3ForEmpty) must equalTo(OK)
        db.readOnlyMaster { implicit s =>
          deviceRepo.getByUserIdAndDeviceTypeAndSignature(user2.id.get, DeviceType.IOS, "ab_iphone6").isDefined === true
          deviceRepo.getByUserId(user2.id.get).length === 2 // one with token, one for ab_galaxynote, one for iphone6
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
