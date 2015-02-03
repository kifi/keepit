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
import com.keepit.model.{ PersonaName, UserPersona, Persona }
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

    "add personas" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, allPersonas) = setupUserPersona
        val userPersonaCommander = inject[UserPersonaCommander]
        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.INVESTOR, PersonaName.TECHIE)
        }
        // add an existing persona
        val mapping1 = userPersonaCommander.addPersonasForUser(user1.id.get, Set(PersonaName.INVESTOR))
        mapping1.size === 0

        // add a new persona
        val mapping2 = userPersonaCommander.addPersonasForUser(user1.id.get, Set(PersonaName.FOODIE))
        mapping2.size === 1

        // add a mixed set
        val mapping3 = userPersonaCommander.addPersonasForUser(user1.id.get, Set(PersonaName.INVESTOR, PersonaName.DEVELOPER, PersonaName.FOODIE))
        mapping3.size === 1

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.INVESTOR, PersonaName.TECHIE, PersonaName.FOODIE, PersonaName.DEVELOPER)
        }
      }
    }

    "remove personas" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, allPersonas) = setupUserPersona
        val userPersonaCommander = inject[UserPersonaCommander]
        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get).map(_.name) === Seq(PersonaName.INVESTOR, PersonaName.TECHIE)
        }
        // remove one existing persona
        userPersonaCommander.removePersonasForUser(user1.id.get, Set(PersonaName.INVESTOR)).size === 1

        // remove a persona that doesn't exist
        userPersonaCommander.removePersonasForUser(user1.id.get, Set(PersonaName.FOODIE)).size === 0

        // remove mixed set
        userPersonaCommander.removePersonasForUser(user1.id.get, Set(PersonaName.INVESTOR, PersonaName.TECHIE, PersonaName.FOODIE)).size === 1

        db.readOnlyMaster { implicit s =>
          userPersonaRepo.getPersonasForUser(user1.id.get) === Seq()
        }
      }
    }
  }

  // sets up a Map of [String, Persona]
  private def setupPersonas()(implicit injector: Injector) = {
    val availablePersonas = Set("techie", "investor", "science_buff", "foodie", "developer")
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
      val user1 = user().withName("Tony", "Stark").withUsername("tonystark").withEmailAddress("tony@stark.com").saved
      userPersonaRepo.save(UserPersona(userId = user1.id.get, personaId = allPersonas("investor").id.get))
      userPersonaRepo.save(UserPersona(userId = user1.id.get, personaId = allPersonas("techie").id.get))
      user1
    }
    (user1, allPersonas)
  }

}
