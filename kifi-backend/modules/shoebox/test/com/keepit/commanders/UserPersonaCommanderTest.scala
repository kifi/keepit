package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.{ UserPersona, Persona }
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._

class UserPersonaCommanderTest extends TestKitSupport with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  def modules = Seq(
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

  "UserPersonaCommander" should {
    "retrieve personas" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, allPersonas) = setupUserPersona
        val userPersonaCommander = inject[UserPersonaCommander]
        val personas = userPersonaCommander.getPersonasForUser(user1.id.get)
        personas.length === 2
        personas.map(_.name) === Seq("hero", "adventurer")
      }
    }

    "add personas" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, allPersonas) = setupUserPersona
        val userPersonaCommander = inject[UserPersonaCommander]
        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getUserPersonaIds(user1.id.get) === Seq(allPersonas("hero"), allPersonas("adventurer"))
        }
        // add an existing persona
        val (personas1, libs1) = userPersonaCommander.addPersonasForUser(user1.id.get, Set("hero"))
        personas1.length === 0
        libs1.length === 0

        // add a new persona
        val (personas2, libs2) = userPersonaCommander.addPersonasForUser(user1.id.get, Set("engineer"))
        personas2.length === 1
        libs2.length === 1

        // add a mixed set
        val (personas3, libs3) = userPersonaCommander.addPersonasForUser(user1.id.get, Set("engineer", "philanthropist", "foodie", "hero"))
        personas3.length === 2
        libs3.length === 2

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getUserPersonaIds(user1.id.get) === Seq(allPersonas("hero"), allPersonas("adventurer"), allPersonas("engineer"), allPersonas("foodie"), allPersonas("philanthropist"))
        }
      }
    }

    "remove personas" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, allPersonas) = setupUserPersona
        val userPersonaCommander = inject[UserPersonaCommander]
        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getUserPersonaIds(user1.id.get) === Seq(allPersonas("hero"), allPersonas("adventurer"))
        }
        // remove one existing persona
        userPersonaCommander.removePersonasForUser(user1.id.get, Set("hero")).length === 1

        // remove a persona that doesn't exist
        userPersonaCommander.removePersonasForUser(user1.id.get, Set("parent")).length === 0

        // remove mixed set
        userPersonaCommander.removePersonasForUser(user1.id.get, Set("hero", "adventurer", "parent")).length === 1

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getUserPersonaIds(user1.id.get) === Seq()
        }
      }
    }
  }

  private def setupPersonas()(implicit injector: Injector) = {
    val availablePersonas = Seq("foodie", "artist", "engineer", "athlete", "parent", "philanthropist", "hero", "adventurer")
    db.readWrite { implicit s =>
      availablePersonas.map { pName =>
        (pName -> personaRepo.save(Persona(name = pName)).id.get)
      }
    }.toMap
  }

  private def setupUserPersona()(implicit injector: Injector) = {
    val allPersonas = setupPersonas
    val user1 = db.readWrite { implicit s =>
      val user1 = user().withName("Tony", "Stark").withUsername("tonystark").withEmailAddress("tony@stark.com").saved
      userPersonaRepo.save(UserPersona(userId = user1.id.get, personaId = allPersonas("hero")))
      userPersonaRepo.save(UserPersona(userId = user1.id.get, personaId = allPersonas("adventurer")))
      user1
    }
    (user1, allPersonas)
  }

}
