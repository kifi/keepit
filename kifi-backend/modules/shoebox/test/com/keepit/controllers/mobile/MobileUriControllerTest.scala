package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.model.{ Restriction, User, NormalizedURIRepo }
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactory._
import com.keepit.model.KeepFactoryHelper._
import play.api.libs.json.{ JsObject, Json }
import play.api.test.FakeRequest
import play.api.test.Helpers._

class MobileUriControllerTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(FakeExecutionContextModule())

  "MobileUriController" should {

    "flag content" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, k1) = setupUris()

        val nUri1 = db.readOnlyMaster { implicit s =>
          val nUri1 = uriIntern.getByUri(k1.url).get
          val targetUri = uriRepo.get(nUri1.id.get)
          targetUri.restriction === None
          nUri1
        }

        val result1 = flagContent(user1, Json.obj("reason" -> "adult", "url" -> k1.url))
        status(result1) must equalTo(NO_CONTENT)

        val resultUriNotFound = flagContent(user1, Json.obj("reason" -> "adult", "url" -> "http://www.notfound.com"))
        status(resultUriNotFound) must equalTo(BAD_REQUEST)
      }
    }

  }

  private def flagContent(user: User, body: JsObject)(implicit injector: Injector) = {
    setUser(user)
    val path = com.keepit.controllers.mobile.routes.MobileUriController.flagContent().url
    val request = FakeRequest("POST", path)
    controller.flagContent()(request.withBody(body))
  }

  private def setupUris()(implicit injector: Injector) = {
    val (user1, keep1) = db.readWrite { implicit s =>
      val user1 = user().withName("Katniss", "Everdeen").saved
      val keep1 = keep().withUser(user1).saved
      uriIntern.getByUri(keep1.url).isDefined === true
      (user1, keep1)
    }
    (user1, keep1)
  }

  private def setUser(user: User)(implicit injector: Injector) = {
    inject[FakeUserActionsHelper].setUser(user)
  }
  private def controller(implicit injector: Injector) = inject[MobileUriController]
  private def uriIntern(implicit injector: Injector) = inject[NormalizedURIInterner]
}
