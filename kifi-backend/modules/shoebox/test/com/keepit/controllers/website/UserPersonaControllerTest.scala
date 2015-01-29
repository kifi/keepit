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
import com.keepit.model.{ PersonaName, UserPersona, Persona }
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

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
          s"""{
              "personas":{
                  "parent":false,
                  "techie":false,
                  "designer":false,
                  "photographer":false,
                  "student":true,
                  "artist":false,
                  "foodie":false,
                  "athlete":false,
                  "gamer":false,
                  "adventurer":true
                }
              }"""
        )
      }
    }

    "add persona" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, allPersonas) = setupUserPersona
        val controller = inject[UserPersonaController]
        inject[FakeUserActionsHelper].setUser(user1)

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.ADVENTURER, PersonaName.STUDENT)
        }

        val testPath = com.keepit.controllers.website.routes.UserPersonaController.addPersona("photographer").url
        val request = FakeRequest("POST", testPath)

        // add new persona
        val result1 = controller.addPersona("photographer")(request)
        status(result1) must equalTo(NO_CONTENT)

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.ADVENTURER, PersonaName.STUDENT, PersonaName.PHOTOGRAPHER)
        }

        // add existing persona
        val result2 = controller.addPersona("photographer")(request)
        status(result2) must equalTo(BAD_REQUEST)
      }
    }

    "remove persona" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, allPersonas) = setupUserPersona
        val controller = inject[UserPersonaController]
        inject[FakeUserActionsHelper].setUser(user1)

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.ADVENTURER, PersonaName.STUDENT)
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
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.ADVENTURER)
        }

      }
    }
  }

  // sets up a Map of [String, Persona]
  private def setupPersonas()(implicit injector: Injector) = {
    val availablePersonas = PersonaName.allPersonas
    db.readWrite { implicit s =>
      availablePersonas.map { pName =>
        (pName -> personaRepo.save(Persona(name = pName)))
      }
    }.toMap
  }

  private def setupUserPersona()(implicit injector: Injector) = {
    val allPersonas = setupPersonas
    val user1 = db.readWrite { implicit s =>
      val user1 = user().withName("Peter", "Parker").withUsername("peterparker").withEmailAddress("peterparker@gmail.com").saved
      userPersonaRepo.save(UserPersona(userId = user1.id.get, personaId = allPersonas(PersonaName.ADVENTURER).id.get))
      userPersonaRepo.save(UserPersona(userId = user1.id.get, personaId = allPersonas(PersonaName.STUDENT).id.get))
      user1
    }
    (user1, allPersonas)
  }
}
