package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import com.keepit.model.{ NotifyPreference, UserNotifyPreferenceRepo, User }
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import play.api.libs.json.{ Json, JsObject }
import play.api.mvc.{ Result, Call }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class MobilePreferenceControllerTest extends Specification with ShoeboxTestInjector {

  val mobileControllerTestModules = Seq()

  "MobileController" should {

    "change recos reminder notifications" in {
      withDb(mobileControllerTestModules: _*) { implicit injector =>
        val notifyPreferenceRepo = inject[UserNotifyPreferenceRepo]
        val user1 = db.readWrite { implicit s =>
          val user1 = user().withName("Lebron", "James").saved
          notifyPreferenceRepo.canNotify(user1.id.get, NotifyPreference.RECOS_REMINDER) === true
          user1
        }

        val result1 = setRecosReminder(user1, Json.obj("recos_reminder" -> false))
        status(result1) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          notifyPreferenceRepo.canNotify(user1.id.get, NotifyPreference.RECOS_REMINDER) === false
        }

        val result2 = setRecosReminder(user1, Json.obj("recos_reminder" -> true))
        status(result2) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          notifyPreferenceRepo.canNotify(user1.id.get, NotifyPreference.RECOS_REMINDER) === true
        }

      }
    }

  }
  private def setRecosReminder(user: User, body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.setNotifyPreferences()(request(routes.MobilePreferenceController.setNotifyPreferences()).withBody(body))
  }
  private def controller(implicit injector: Injector) = inject[MobilePreferenceController]
  private def request(route: Call) = FakeRequest(route.method, route.url)
}
