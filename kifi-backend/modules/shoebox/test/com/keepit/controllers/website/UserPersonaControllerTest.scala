package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId, FakeCryptoModule }
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.util.Success

class UserPersonaControllerTest extends Specification with ShoeboxTestInjector {

  implicit val context = HeimdalContext.empty
  def controllerTestModules = Seq(
    FakeExecutionContextModule(),
    FakeScrapeSchedulerModule(),
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
        val controller = inject[UserPersonaController]
        inject[FakeUserActionsHelper].setUser(user1)

        val testPath = com.keepit.controllers.website.routes.UserPersonaController.getAllPersonas().url
        val request1 = FakeRequest("GET", testPath)
        val result1 = controller.getAllPersonas()(request1)
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
        val controller = inject[UserPersonaController]
        inject[FakeUserActionsHelper].setUser(user1)

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.ARTIST, PersonaName.STUDENT)
        }

        val testPath = com.keepit.controllers.website.routes.UserPersonaController.addPersona("science_buff").url
        val request = FakeRequest("POST", testPath)

        // add new persona
        val result1 = controller.addPersona("science_buff")(request)
        status(result1) must equalTo(NO_CONTENT)

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.ARTIST, PersonaName.STUDENT, PersonaName.SCIENCE_BUFF)
        }

        // add existing persona
        val result2 = controller.addPersona("science_buff")(request)
        status(result2) must equalTo(BAD_REQUEST)
      }
    }

    "remove persona" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, allPersonas) = setupUserPersona
        val controller = inject[UserPersonaController]
        inject[FakeUserActionsHelper].setUser(user1)

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.ARTIST, PersonaName.STUDENT)
        }

        val testPath = com.keepit.controllers.website.routes.UserPersonaController.removePersona("student").url
        val request = FakeRequest("DELETE", testPath)

        // remove existing persona
        val result1 = controller.removePersona("student")(request)
        status(result1) must equalTo(NO_CONTENT)

        // remove persona that doesn't exist
        val result2 = controller.removePersona("student")(request)
        status(result2) must equalTo(BAD_REQUEST)

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.ARTIST)
        }
      }
    }

    "get default keep for persona" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]

        val (user1, allPersonas) = setupUserPersona
        val controller = inject[UserPersonaController]
        inject[FakeUserActionsHelper].setUser(user1)

        val testPath = com.keepit.controllers.website.routes.UserPersonaController.getDefaultKeepForPersona("science_buff").url

        // get for inactive persona
        val request1 = FakeRequest("GET", testPath)
        val result1 = controller.getDefaultKeepForPersona("science_buff")(request1)
        status(result1) must equalTo(BAD_REQUEST)

        // add persona, and get for now active persona
        val requestAdd = FakeRequest("POST", com.keepit.controllers.website.routes.UserPersonaController.addPersona("science_buff").url)
        val resultAdd = controller.addPersona("science_buff")(requestAdd)
        status(resultAdd) must equalTo(NO_CONTENT)

        val (_, personaLib) = db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.ARTIST, PersonaName.STUDENT, PersonaName.SCIENCE_BUFF)
          libraryRepo.getByUser(user1.id.get).head // created from adding persona
        }

        val request2 = FakeRequest("GET", testPath)
        val result2 = controller.getDefaultKeepForPersona("science_buff")(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        val resultJson = contentAsJson(result2)
        (resultJson \ "keep").as[DefaultPersonaKeep].site === "ted.com"
        Library.decodePublicId((resultJson \ "libraryId").asOpt[PublicId[Library]].get) === Success(personaLib.id.get)
      }
    }

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
