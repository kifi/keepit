package com.keepit.controllers.website

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
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsObject, Json }
import play.api.mvc.{ Call, Result }
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scala.concurrent.Future

class UserPersonaControllerTest extends Specification with ShoeboxTestInjector {

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

  "UserPersonaController" should {

    "get all personas" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, allPersonas) = setupUserPersona

        val result1 = getAllPersonas(user1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        contentAsJson(result1) === Json.parse(
          s"""{"personas":[
                {
                  "id":"artist",
                  "displayName":"artist",
                  "displayNamePlural":"artists",
                  "selected":true,
                  "iconPath":"icon/artist.jpg",
                  "activeIconPath":"icon/active_artist.jpg"
                },
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
                }
              ]}"""
        )
      }
    }

    "add persona" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, allPersonas) = setupUserPersona

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.ARTIST, PersonaName.STUDENT)
        }

        // add new persona
        val result1 = addPersona(user1, "science_buff")
        status(result1) must equalTo(NO_CONTENT)

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.ARTIST, PersonaName.STUDENT, PersonaName.SCIENCE_BUFF)
        }

        // add existing persona
        val result2 = addPersona(user1, "science_buff")
        status(result2) must equalTo(BAD_REQUEST)
      }
    }

    "remove persona" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, allPersonas) = setupUserPersona

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.ARTIST, PersonaName.STUDENT)
        }

        // remove existing persona
        val result1 = removePersona(user1, "student")
        status(result1) must equalTo(NO_CONTENT)

        // remove persona that doesn't exist
        val result2 = removePersona(user1, "student")
        status(result2) must equalTo(BAD_REQUEST)

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.ARTIST)
        }
      }
    }

    "select user persona" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, allPersonas) = setupUserPersona
        val controller = inject[UserPersonaController]
        inject[FakeUserActionsHelper].setUser(user1)

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.ARTIST, PersonaName.STUDENT)
        }

        // add new persona "artist", remove "student"
        val result1 = selectPersonas(user1, Json.obj("personas" -> Seq("student", "science_buff")))
        status(result1) must equalTo(OK)
        val resultJson1 = contentAsJson(result1)
        (resultJson1 \ "added").as[Seq[String]] === Seq("science_buff")
        (resultJson1 \ "removed").as[Seq[String]] === Seq("artist")

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.STUDENT, PersonaName.SCIENCE_BUFF)
        }

      }
    }
  }

  private def controller(implicit injector: Injector) = inject[UserPersonaController]
  private def request(route: Call) = FakeRequest(route.method, route.url)

  private def getAllPersonas(user: User)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.getAllPersonas()(request(routes.UserPersonaController.getAllPersonas()))
  }

  private def addPersona(user: User, name: String)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.addPersona(name)(request(routes.UserPersonaController.addPersona(name)))
  }

  private def removePersona(user: User, name: String)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.removePersona(name)(request(routes.UserPersonaController.removePersona(name)))
  }

  private def selectPersonas(user: User, body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.selectPersonas()(request(routes.UserPersonaController.selectPersonas()).withBody(body))
  }

  // sets up a Map of [String, Persona]
  private def setupPersonas()(implicit injector: Injector) = {
    val availablePersonas = Set("artist", "science_buff", "student", "foodie")
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
      userPersonaRepo.save(UserPersona(userId = user1.id.get, personaId = allPersonas("artist").id.get))
      userPersonaRepo.save(UserPersona(userId = user1.id.get, personaId = allPersonas("student").id.get))
      user1
    }
    (user1, allPersonas)
  }
}
