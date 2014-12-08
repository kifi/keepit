package com.keepit.shoebox

import scala.concurrent.Await
import scala.concurrent.duration._

import org.specs2.mutable.Specification

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.net.{ FakeHttpClientModule, FakeClientResponse, HttpUri }
import com.keepit.model._
import com.keepit.model.UserFactory._
import com.keepit.search.Lang
import com.keepit.test.CommonTestInjector

import play.api.libs.json._
import com.keepit.common.cache.FakeCacheModule

class ShoeboxServiceClientTest extends Specification with CommonTestInjector {

  val user1965 = user().withId(1965).get
  val user1933 = user().withId(1933).get
  val users = Seq(user1965, user1933)
  val phrases = Seq(
    Phrase(phrase = "grandeur extensive", lang = Lang("fr"), source = "physique statistique"),
    Phrase(phrase = "gaz parfait", lang = Lang("fr"), source = "physique statistique")
  )

  val fakeShoeboxResponse: PartialFunction[HttpUri, FakeClientResponse] = {
    case s if s.url.contains("/internal/shoebox/database/getConnectedUsers") && s.url.contains("1965") => "[1933,1935,1927,1921]"
    case s if s.url.contains("/internal/shoebox/database/searchFriends") && s.url.contains("1965") => "[1933,1935,1927,1921]"
    case s if s.url.contains("/internal/shoebox/database/getUsers") && s.url.contains("1965%2C1933") => Json.stringify(Json.toJson(users))
    case s if s.url.contains("/internal/shoebox/database/getPhrasesChanged") && s.url.contains("seqNum=0&fetchSize=4") => Json.stringify(Json.toJson(phrases))
    case s if s.url.contains("/internal/shoebox/database/getUsersByExperiment") => Json.stringify(Json.toJson(users))
  }

  "ShoeboxServiceClient" should {

    val shoeboxServiceClientTestModules = Seq(FakeHttpClientModule(fakeShoeboxResponse), ProdShoeboxServiceClientModule(), FakeCacheModule())

    "get users" in {
      withInjector(shoeboxServiceClientTestModules: _*) { implicit injector =>
        val shoeboxServiceClient = inject[ShoeboxServiceClient]
        val usersFuture = shoeboxServiceClient.getUsers(users.map(_.id.get))
        Await.result(usersFuture, Duration(5, SECONDS)) === users

      }
    }

    "get friends' ids" in {
      withInjector(shoeboxServiceClientTestModules: _*) { implicit injector =>
        val shoeboxServiceClient = inject[ShoeboxServiceClient]
        val userIdsFuture = shoeboxServiceClient.getFriends(user1965.id.get)
        Await.result(userIdsFuture, Duration(5, SECONDS)) === Set(1933, 1935, 1927, 1921).map(Id[User](_))
      }
    }

    "get phrases by page" in {
      withInjector(shoeboxServiceClientTestModules: _*) { implicit injector =>
        val shoeboxServiceClient = inject[ShoeboxServiceClient]
        val phrasesFuture = shoeboxServiceClient.getPhrasesChanged(SequenceNumber(0), 4)
        Await.result(phrasesFuture, Duration(5, SECONDS)) === phrases

      }
    }

    "getUsersByExperiment" in {
      withInjector(shoeboxServiceClientTestModules: _*) { implicit injector =>
        val shoeboxServiceClient = inject[ShoeboxServiceClient]
        val future = shoeboxServiceClient.getUsersByExperiment(ExperimentType.ADMIN)
        Await.result(future, Duration(5, SECONDS)) === users.toSet
      }
    }

  }
}
