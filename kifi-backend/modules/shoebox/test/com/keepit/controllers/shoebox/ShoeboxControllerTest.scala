package com.keepit.controllers.shoebox

import org.specs2.mutable.Specification
import play.api.test.Helpers._
import com.keepit.common.db.slick._
import com.keepit.common.social.BasicUserRepo
import com.keepit.test.{InjectedDbRepos, EmptyApplication}
import com.keepit.inject._
import com.keepit.model._
import play.api.libs.json.{Json, JsNumber, JsArray}
import com.keepit.serializer.UserSerializer
import com.keepit.model.User
import play.api.test.FakeRequest
import com.keepit.search.Lang

class ShoeboxControllerTest extends Specification with ApplicationInjector with InjectedDbRepos {

  def setupSomeUsers() = {
    inject[Database].readWrite {implicit s =>

      val user1965 = userRepo.save(User(firstName="Richard",lastName="Feynman"))
      val user1933 = userRepo.save(User(firstName="Paul",lastName="Dirac"))
      val user1935 = userRepo.save(User(firstName="James",lastName="Chadwick"))
      val user1927 = userRepo.save(User(firstName="Arthur",lastName="Compton"))
      val user1921 = userRepo.save(User(firstName="Albert",lastName="Einstein"))
      val friends = List(user1933,user1935,user1927,user1921)

      friends.foreach {friend => userConnRepo.save(UserConnection(user1=user1965.id.get,user2=friend.id.get))}
      (user1965,friends)
    }
  }

  def setupSomePhrases() = {
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
        running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule().withFakeHttpClient()) {
          val (user1965,friends) = setupSomeUsers()
          val users = user1965::friends
          val shoeboxController = inject[ShoeboxController]
          val query = users.map(_.id.get).mkString(",")
          val result = shoeboxController.getUsers(query)(FakeRequest())
          status(result) must equalTo(OK);
          contentType(result) must beSome("application/json");
          contentAsString(result) must equalTo(JsArray(users.map(UserSerializer.userSerializer.writes)).toString())
        }
    }

    "return basic users from the database" in {
        running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule().withFakeHttpClient()) {
          val (user1965,friends) = setupSomeUsers()
          val users = user1965::friends
          val basicUserRepo = inject[BasicUserRepo]
          val basicUsersJson = inject[Database].readOnly { implicit s =>
            users.map{ u => (u.id.get.id.toString -> Json.toJson(basicUserRepo.load(u.id.get))) }.toMap
          }
          val shoeboxController = inject[ShoeboxController]
          val query = users.map(_.id.get).mkString(",")
          val result = shoeboxController.getBasicUsers(query)(FakeRequest())
          status(result) must equalTo(OK);
          contentType(result) must beSome("application/json");
          contentAsString(result) must equalTo(Json.toJson(basicUsersJson).toString())
        }
    }

    "return connected users' ids from the database" in {
      running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule().withFakeHttpClient()) {
        val (user1965,friends) = setupSomeUsers()
        val shoeboxController = inject[ShoeboxController]
        val result = shoeboxController.getConnectedUsers(user1965.id.get)(FakeRequest())
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");
        contentAsString(result) must equalTo(JsArray(friends.map(friend => JsNumber(friend.id.get.id))).toString())
      }
    }

    "return phrases from the database" in {
      running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule().withFakeHttpClient()) {
        setupSomePhrases()
        val shoeboxController = inject[ShoeboxController]
        val result = shoeboxController.getPhrasesByPage(0,2)(FakeRequest())
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
