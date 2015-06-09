package com.keepit.controllers.internal

import com.keepit.common.json.TupleFormat
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.social.BasicUser
import org.specs2.mutable.Specification

import com.keepit.common.db.slick._
import com.keepit.common.social.{ FakeSocialGraphModule, BasicUserRepo }
import com.keepit.model._
import com.keepit.search.{ FakeSearchServiceClientModule, Lang }
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import com.keepit.common.controller._

import play.api.libs.json.{ Json, JsNumber, JsArray }
import play.api.test.{ FakeHeaders, FakeRequest }
import play.api.test.Helpers._
import com.google.inject.Injector
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.analytics.FakeAnalyticsModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.common.crypto.FakeCryptoModule

class ShoeboxControllerTest extends Specification with ShoeboxTestInjector {

  val shoeboxControllerTestModules = Seq(
    DevDataPipelineExecutorModule(),
    FakeShoeboxServiceModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeAnalyticsModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeSearchServiceClientModule(),
    FakeAirbrakeModule(),
    FakeUserActionsModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeCortexServiceClientModule(),
    FakeKeepImportsModule(),
    FakeCryptoModule(),
    FakeCuratorServiceClientModule()
  )

  def setupSomeUsers()(implicit injector: Injector) = {
    db.readWrite { implicit s =>

      val user1965 = UserFactory.user().withName("Richard", "Feynman").withUsername("test").saved
      val user1933 = UserFactory.user().withName("Paul", "Dirac").withUsername("test2").saved
      val user1935 = UserFactory.user().withName("James", "Chadwick").withUsername("test3").saved
      val user1927 = UserFactory.user().withName("Arthur", "Compton").withUsername("test4").saved
      val user1921 = UserFactory.user().withName("Albert", "Einstein").withUsername("test5").saved
      val friends = List(user1933, user1935, user1927, user1921)

      friends.foreach { friend => userConnRepo.save(UserConnection(user1 = user1965.id.get, user2 = friend.id.get)) }
      (user1965, friends)
    }
  }

  def setupSomePhrases()(implicit injector: Injector) = {
    val phrases = db.readWrite { implicit s =>
      val p1 = phraseRepo.save(Phrase(phrase = "planck constant", lang = Lang("en"), source = "quantum physics"))
      Thread.sleep(2) // need a bit of space so sequence numbers do not get mixed up
      val p2 = phraseRepo.save(Phrase(phrase = "wave-particle duality", lang = Lang("en"), source = "quantum physics"))
      Thread.sleep(2)
      val p3 = phraseRepo.save(Phrase(phrase = "schrodinger equation", lang = Lang("en"), source = "quantum physics"))
      Thread.sleep(2)
      val p4 = phraseRepo.save(Phrase(phrase = "hypothèse ergodique", lang = Lang("fr"), source = "physique statistique"))
      Thread.sleep(2)
      val p5 = phraseRepo.save(Phrase(phrase = "grandeur extensive", lang = Lang("fr"), source = "physique statistique"))
      Thread.sleep(2)
      val p6 = phraseRepo.save(Phrase(phrase = "gaz parfait", lang = Lang("fr"), source = "physique statistique"))
      List(p1, p2, p3, p4, p5, p6)
    }
    inject[PhraseSequenceNumberAssigner].assignSequenceNumbers()
    phrases
  }

  "ShoeboxController" should {

    "return users from the database" in {
      withDb(shoeboxControllerTestModules: _*) { implicit injector =>
        val (user1965, friends) = setupSomeUsers()
        val users = user1965 :: friends
        val query = users.map(_.id.get).mkString(",")
        val result = inject[ShoeboxController].getUsers(query)(FakeRequest())
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");
        contentAsString(result) must equalTo(JsArray(users.map(Json.toJson(_))).toString())
      }
    }

    "return basic users from the database" in {
      withDb(shoeboxControllerTestModules: _*) { implicit injector =>
        val (user1965, friends) = setupSomeUsers()
        val users = user1965 :: friends
        val basicUserRepo = inject[BasicUserRepo]
        val basicUsersJson = inject[Database].readOnlyMaster { implicit s =>
          val basicUsers = basicUserRepo.loadAll(users.map(_.id.get).toSet)
          implicit val tuplewrites = TupleFormat.tuple2Writes[Id[User], BasicUser]
          Json.toJson(basicUsers.toSeq)
        }

        val payload = JsArray(users.map(_.id.get).map(x => JsNumber(x.id)))
        val result = inject[ShoeboxController].getBasicUsers()(FakeRequest().withBody(payload))
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");
        contentAsString(result) must equalTo(basicUsersJson.toString)
      }
    }

    "return connected users' ids from the database" in {
      withDb(shoeboxControllerTestModules: _*) { implicit injector =>
        val (user1965, friends) = setupSomeUsers()
        val result = inject[ShoeboxController].getConnectedUsers(user1965.id.get)(FakeRequest())
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");
        contentAsString(result) must equalTo(JsArray(friends.map(friend => JsNumber(friend.id.get.id))).toString())
      }
    }

    "return phrases changed from the database" in {
      withDb(shoeboxControllerTestModules: _*) { implicit injector =>
        setupSomePhrases()
        val result = inject[ShoeboxDataPipeController].getPhrasesChanged(SequenceNumber(4), 2)(FakeRequest())
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");
        contentAsString(result) must contain("gaz parfait");
        contentAsString(result) must contain("grandeur extensive");
        contentAsString(result) must not contain ("hypothèse ergodique");
        contentAsString(result) must not contain ("schrodinger equation");
        contentAsString(result) must not contain ("wave-particle duality");
        contentAsString(result) must not contain ("planck constant")
      }
    }

    "getPrimaryEmailAddressForUsers" should {
      "return a map of user id -> EmailAddress" in {
        withDb(shoeboxControllerTestModules: _*) { implicit injector =>
          val call = com.keepit.controllers.internal.routes.ShoeboxController.getPrimaryEmailAddressForUsers()
          call.method === "POST"
          call.url === s"/internal/shoebox/database/getPrimaryEmailAddressForUsers"

          val userEmails = db.readWrite { implicit rw =>
            for (i <- 1 to 3) yield UserFactory.user().withName(s"first$i", s"last$i").withEmailAddress(s"test$i@yahoo.com").withUsername(s"test$i").saved
          }.map(user => user.id.get -> user.primaryEmail).toMap

          val userIds = userEmails.keySet
          val payload = Json.toJson(userIds)
          val ctrl = inject[ShoeboxController]
          val result = ctrl.getPrimaryEmailAddressForUsers()(FakeRequest("POST", call.url, FakeHeaders(), payload))
          status(result) must equalTo(OK)

          val json = Json.parse(contentAsString(result))
          Json.fromJson[Map[Id[User], Option[EmailAddress]]](json).get === userEmails
        }
      }
    }
  }
}
