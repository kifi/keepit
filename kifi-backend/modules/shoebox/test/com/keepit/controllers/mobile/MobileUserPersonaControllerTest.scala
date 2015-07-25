package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.{ PersonaName, UserPersona, Persona }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

class MobileUserPersonaControllerTest extends Specification with ShoeboxTestInjector {

  implicit val context = HeimdalContext.empty
  def controllerTestModules = Seq(
    FakeExecutionContextModule(),
    FakeSearchServiceClientModule(),
    FakeMailModule(),
    FakeShoeboxStoreModule(),
    FakeCryptoModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeHeimdalServiceClientModule()
  )

  "MobileUserPersonaController" should {

    "get all personas" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, allPersonas) = setupUserPersona
        val controller = inject[MobileUserPersonaController]
        inject[FakeUserActionsHelper].setUser(user1)

        val testPath = com.keepit.controllers.mobile.routes.MobileUserPersonaController.getAllPersonas().url
        val request = FakeRequest("GET", testPath)
        val result1 = controller.getAllPersonas()(request)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        contentAsJson(result1) === Json.parse(
          s"""{"personas":[
                {
                  "id":"science_buff",
                  "displayName":"science buff",
                  "displayNamePlural":"science buffs",
                  "selected":false,
                  "iconPath":"icon/science_buff.jpg",
                  "activeIconPath":"icon/active_science_buff.jpg"
                },
                {
                  "id":"student",
                  "displayName":"student",
                  "displayNamePlural":"students",
                  "selected":true,
                  "iconPath":"icon/student.jpg",
                  "activeIconPath":"icon/active_student.jpg"
                },
                {
                  "id":"foodie",
                  "displayName":"foodie",
                  "displayNamePlural":"foodies",
                  "selected":false,
                  "iconPath":"icon/foodie.jpg",
                  "activeIconPath":"icon/active_foodie.jpg"
                },
                {
                  "id":"techie",
                  "displayName":"techie",
                  "displayNamePlural":"techies",
                  "selected":true,
                  "iconPath":"icon/techie.jpg",
                  "activeIconPath":"icon/active_techie.jpg"
                }
              ]}"""
        )

        // view as anonymous user (all personas should be unset)
        inject[FakeUserActionsHelper].unsetUser()
        val result2 = controller.getAllPersonas()(request)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        (contentAsJson(result2) \\ "selected").map(_.as[Boolean]) === Seq(false, false, false, false)
      }
    }

    "select user persona" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, allPersonas) = setupUserPersona
        val controller = inject[MobileUserPersonaController]
        inject[FakeUserActionsHelper].setUser(user1)

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.TECHIE, PersonaName.STUDENT)
        }

        val testPath = com.keepit.controllers.mobile.routes.MobileUserPersonaController.selectPersonas().url
        val request = FakeRequest("POST", testPath)

        // add new persona "artist", remove "student"
        val result1 = controller.selectPersonas()(request.withBody(Json.obj("personas" -> Seq("techie", "science_buff"))))
        status(result1) must equalTo(OK)
        val resultJson1 = contentAsJson(result1)
        (resultJson1 \ "added").as[Seq[String]] === Seq("science_buff")
        (resultJson1 \ "removed").as[Seq[String]] === Seq("student")

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.TECHIE, PersonaName.SCIENCE_BUFF)
        }

      }
    }

  }

  // sets up a Map of [String, Persona]
  private def setupPersonas()(implicit injector: Injector) = {
    val availablePersonas = Set("science_buff", "student", "foodie", "techie")
    db.readWrite { implicit s =>
      availablePersonas.map { pName =>
        val iconPath = "icon/" + pName + ".jpg"
        val activeIconPath = "icon/active_" + pName + ".jpg"
        val displayName = pName.replace("_", " ")
        (pName -> personaRepo.save(Persona(
          name = PersonaName(pName),
          displayName = displayName,
          displayNamePlural = displayName + "s",
          iconPath = iconPath,
          activeIconPath = activeIconPath)))
      }
    }.toMap
  }

  private def setupUserPersona()(implicit injector: Injector) = {
    val allPersonas = setupPersonas
    val user1 = db.readWrite { implicit s =>
      val user1 = user().withName("Peter", "Parker").withUsername("peterparker").withEmailAddress("peterparker@gmail.com").saved
      userPersonaRepo.save(UserPersona(userId = user1.id.get, personaId = allPersonas("techie").id.get))
      userPersonaRepo.save(UserPersona(userId = user1.id.get, personaId = allPersonas("student").id.get))
      user1
    }
    (user1, allPersonas)
  }
}
