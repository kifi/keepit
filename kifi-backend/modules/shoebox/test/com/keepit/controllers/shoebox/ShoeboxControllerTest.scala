package com.keepit.controllers.internal

import org.specs2.mutable.Specification

import com.keepit.common.db.slick._
import com.keepit.common.social.{FakeShoeboxSecureSocialModule, FakeSocialGraphModule, BasicUserRepo}
import com.keepit.model._
import com.keepit.search.{TestSearchServiceClientModule, Lang}
import com.keepit.test.{ShoeboxApplication, ShoeboxApplicationInjector}
import com.keepit.common.controller._

import play.api.Play
import play.api.libs.json.{Json, JsNumber, JsArray}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import com.google.inject.Injector
import com.keepit.shoebox.{ShoeboxSlickModule, FakeShoeboxServiceModule}
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.analytics.TestAnalyticsModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import scala.concurrent.ExecutionContext.Implicits.global
import com.keepit.abook.TestABookServiceClientModule
import com.keepit.scraper.{TestScrapeSchedulerConfigModule, TestScraperServiceClientModule, FakeScrapeSchedulerModule}
import com.keepit.common.db.SequenceNumber
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.cortex.FakeCortexServiceClientModule

class ShoeboxControllerTest extends Specification with ShoeboxApplicationInjector {

  val shoeboxControllerTestModules = Seq(
    ShoeboxSlickModule(),
    FakeShoeboxServiceModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    TestAnalyticsModule(),
    ShoeboxFakeStoreModule(),
    TestActorSystemModule(),
    TestSearchServiceClientModule(),
    FakeAirbrakeModule(),
    FakeActionAuthenticatorModule(),
    FakeShoeboxSecureSocialModule(),
    TestABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeScrapeSchedulerModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    TestScraperServiceClientModule(),
    TestScrapeSchedulerConfigModule()
  )

  def setupSomeUsers()(implicit injector: Injector) = {
    inject[Database].readWrite {implicit s =>

      val user1965 = userRepo.save(User(firstName="Richard",lastName="Feynman"))
      val user1933 = userRepo.save(User(firstName="Paul",lastName="Dirac"))
      val user1935 = userRepo.save(User(firstName="James",lastName="Chadwick"))
      val user1927 = userRepo.save(User(firstName="Arthur",lastName="Compton"))
      val user1921 = userRepo.save(User(firstName="Albert",lastName="Einstein"))
      val friends = List(user1933,user1935,user1927,user1921)

      friends.foreach {friend => userConnRepo.save(UserConnection(user1=user1965.id.get,user2=friend.id.get))}
      (user1965, friends)
    }
  }

  def setupSomePhrases()(implicit injector: Injector) = {
    inject[Database].readWrite {implicit s =>
      val phrases = List(
        phraseRepo.save(Phrase(phrase="planck constant", lang=Lang("en"), source="quantum physics")),
        phraseRepo.save(Phrase(phrase="wave-particle duality", lang=Lang("en"), source="quantum physics")),
        phraseRepo.save(Phrase(phrase="schrodinger equation", lang=Lang("en"), source="quantum physics")),
        phraseRepo.save(Phrase(phrase="hypothèse ergodique", lang=Lang("fr"), source="physique statistique")),
        phraseRepo.save(Phrase(phrase="grandeur extensive", lang=Lang("fr"), source="physique statistique")),
        phraseRepo.save(Phrase(phrase="gaz parfait", lang=Lang("fr"), source="physique statistique"))
      )
      phrases
    }
  }

  "ShoeboxController" should {

    "return users from the database" in {
        running(new ShoeboxApplication(shoeboxControllerTestModules:_*)) {
          val (user1965, friends) = setupSomeUsers()
          val users = user1965::friends
          val shoeboxController = inject[ShoeboxController]
          val query = users.map(_.id.get).mkString(",")
          val result = shoeboxController.getUsers(query)(FakeRequest())
          status(result) must equalTo(OK);
          contentType(result) must beSome("application/json");
          contentAsString(result) must equalTo(JsArray(users.map(Json.toJson(_))).toString())
        }
    }

    "return basic users from the database" in {
      running(new ShoeboxApplication(shoeboxControllerTestModules:_*)) {
        val (user1965, friends) = setupSomeUsers()
        val users = user1965::friends
        val basicUserRepo = inject[BasicUserRepo]
        val basicUsersJson = inject[Database].readOnly { implicit s =>
          users.map{ u => (u.id.get.id.toString -> Json.toJson(basicUserRepo.load(u.id.get))) }.toMap
        }

        val query = users.map(_.id.get).mkString(",")
        val payload = JsArray(users.map(_.id.get).map(x => JsNumber(x.id)))
        val path = com.keepit.controllers.internal.routes.ShoeboxController.getBasicUsers().toString
        val result = route(FakeRequest("POST", path).withJsonBody(payload)).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");
        contentAsString(result) must equalTo(Json.toJson(basicUsersJson).toString())
      }
    }

    "return connected users' ids from the database" in {
      running(new ShoeboxApplication(shoeboxControllerTestModules:_*)) {
        val (user1965, friends) = setupSomeUsers()
        val shoeboxController = inject[ShoeboxController]
        val result = shoeboxController.getConnectedUsers(user1965.id.get)(FakeRequest())
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");
        contentAsString(result) must equalTo(JsArray(friends.map(friend => JsNumber(friend.id.get.id))).toString())
      }
    }

    "return phrases changed from the database" in {
      running(new ShoeboxApplication(shoeboxControllerTestModules:_*)) {
        setupSomePhrases()
        val route = com.keepit.controllers.internal.routes.ShoeboxDataPipeController.getPhrasesChanged(SequenceNumber(4) , 2).toString
        route === "/internal/shoebox/database/getPhrasesChanged?seqNum=4&fetchSize=2"
        val shoeboxController = inject[ShoeboxDataPipeController]
        val result = shoeboxController.getPhrasesChanged(SequenceNumber(4), 2)(FakeRequest())
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");
        contentAsString(result) must contain("gaz parfait");
        contentAsString(result) must contain("grandeur extensive");
        contentAsString(result) must not contain("hypothèse ergodique");
        contentAsString(result) must not contain("schrodinger equation");
        contentAsString(result) must not contain("wave-particle duality");
        contentAsString(result) must not contain("planck constant")
      }
    }
  }
}
