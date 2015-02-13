package com.keepit.controllers.admin

import com.google.inject.Injector
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.ExecutionContextModule
import com.keepit.common.controller.{ FakeUserActionsModule, FakeUserActionsHelper }
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.common.social.{ FakeShoeboxAppSecureSocialModule }
import com.keepit.model.UserFactory._
import com.keepit.model._
import com.keepit.scraper.{ FakeScraperServiceClientModule, FakeScrapeSchedulerModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ ShoeboxApplicationInjector, ShoeboxApplication }
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

class AdminPersonaControllerTest extends Specification with ShoeboxApplicationInjector {
  val modules = Seq(
    ExecutionContextModule(),
    FakeUserActionsModule(),
    FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeHttpClientModule(),
    FakeMailModule(),
    FakeSearchServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeShoeboxAppSecureSocialModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeCuratorServiceClientModule())

  "AdminPersonaController" should {
    "get all personas" in {
      running(new ShoeboxApplication(modules: _*)) {
        val (user1, allPersonas) = setupUserPersona
        val controller = inject[AdminPersonaController]
        inject[FakeUserActionsHelper].setUser(user1, experiments = Set(ExperimentType.ADMIN))

        val testPath = com.keepit.controllers.admin.routes.AdminPersonaController.getAllPersonas().url
        val request1 = FakeRequest("GET", testPath)
        val result1 = controller.getAllPersonas()(request1)
        status(result1) must equalTo(OK)
      }
    }

    "create new persona" in {
      running(new ShoeboxApplication(modules: _*)) {
        val (user1, allPersonas) = setupUserPersona
        val controller = inject[AdminPersonaController]
        val testPath = com.keepit.controllers.admin.routes.AdminPersonaController.createPersona().url
        inject[FakeUserActionsHelper].setUser(user1, experiments = Set(ExperimentType.ADMIN))

        db.readOnlyMaster { implicit s =>
          personaRepo.all.length === 4
        }

        val jsonBody = Json.obj(
          "name" -> "developer",
          "displayName" -> "developer",
          "iconPath" -> "/icon/0.jpg",
          "activeIconPath" -> "/icon/0.jpg"
        )

        // create new persona
        val request1 = FakeRequest("POST", testPath).withBody(jsonBody)
        val result1 = route(request1).get
        status(result1) must equalTo(OK)

        db.readOnlyMaster { implicit s =>
          personaRepo.all.length === 5
        }

        // submit same persona
        val request2 = FakeRequest("POST", testPath).withBody(jsonBody)
        val result2 = route(request2).get
        status(result2) must equalTo(OK)

        db.readOnlyMaster { implicit s =>
          personaRepo.all.length === 5
        }
      }
    }

    "edit persona" in {
      running(new ShoeboxApplication(modules: _*)) {
        val (user1, allPersonas) = setupUserPersona
        val controller = inject[AdminPersonaController]

        inject[FakeUserActionsHelper].setUser(user1, experiments = Set(ExperimentType.ADMIN))

        db.readOnlyMaster { implicit s =>
          personaRepo.getByState(PersonaStates.ACTIVE).length === 4
          val changedPersona = personaRepo.getByName(PersonaName("foodie")).get
          changedPersona.name.value === "foodie"
          changedPersona.displayName === "foodie"
          changedPersona.iconPath === "icon/foodie.jpg"
          changedPersona.activeIconPath === "icon/active_foodie.jpg"
        }

        // edit persona info
        val testPath = com.keepit.controllers.admin.routes.AdminPersonaController.editPersona(allPersonas("foodie").id.get).url
        val request1 = FakeRequest("POST", testPath).withBody(Json.obj(
          "displayName" -> "chef",
          "iconPath" -> "icon/asdf.jpg"
        ))
        val result1 = route(request1).get
        status(result1) must equalTo(OK)

        db.readOnlyMaster { implicit s =>
          personaRepo.getByState(PersonaStates.ACTIVE).length === 4
          val changedPersona = personaRepo.getByName(PersonaName("foodie")).get
          changedPersona.name.value === "foodie"
          changedPersona.displayName === "chef"
          changedPersona.iconPath === "icon/asdf.jpg"
          changedPersona.activeIconPath === "icon/active_foodie.jpg"
        }

        // deactivate persona
        val request2 = FakeRequest("POST", testPath).withBody(Json.obj(
          "state" -> "inactive"
        ))
        val result2 = route(request2).get
        status(result2) must equalTo(OK)

        db.readOnlyMaster { implicit s =>
          personaRepo.getByState(PersonaStates.ACTIVE).length === 3
          personaRepo.getByName(PersonaName("foodie")).isDefined === false
        }

        // reactivate persona
        val request3 = FakeRequest("POST", testPath).withBody(Json.obj(
          "displayName" -> "chef1",
          "state" -> "active"
        ))
        val result3 = route(request3).get
        status(result3) must equalTo(OK)

        db.readOnlyMaster { implicit s =>
          personaRepo.getByState(PersonaStates.ACTIVE).length === 4
          val changedPersona = personaRepo.getByName(PersonaName("foodie")).get
          changedPersona.displayName === "chef1"
        }
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
