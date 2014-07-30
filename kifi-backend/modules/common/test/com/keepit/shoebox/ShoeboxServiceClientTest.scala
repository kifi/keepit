package com.keepit.shoebox

import scala.concurrent.Await
import scala.concurrent.duration._

import org.specs2.mutable.Specification

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.net.{ FakeHttpClientModule, FakeClientResponse, HttpUri }
import com.keepit.inject._
import com.keepit.model._
import com.keepit.search.Lang
import com.keepit.test.CommonTestApplication

import play.api.libs.json._
import play.api.test.Helpers._
import com.keepit.common.cache.TestCacheModule

class ShoeboxServiceClientTest extends Specification with ApplicationInjector {

  val user1965 = User(firstName = "Richard", lastName = "Feynman", username = Some(Username("dickyfey"))).withId(Id[User](1965))
  val user1933 = User(firstName = "Paul", lastName = "Dirac", username = Some(Username("cyberpaul1992"))).withId(Id[User](1933))
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
  }

  "ShoeboxServiceClient" should {

    val shoeboxServiceClientTestModules = Seq(FakeHttpClientModule(fakeShoeboxResponse), ProdShoeboxServiceClientModule(), TestCacheModule())

    "get users" in {
      running(new CommonTestApplication(shoeboxServiceClientTestModules: _*)) {
        val shoeboxServiceClient = inject[ShoeboxServiceClient]
        val usersFuture = shoeboxServiceClient.getUsers(users.map(_.id.get))
        Await.result(usersFuture, Duration(5, SECONDS)) === users

      }
    }

    "get friends' ids" in {
      running(new CommonTestApplication(shoeboxServiceClientTestModules: _*)) {
        val shoeboxServiceClient = inject[ShoeboxServiceClient]
        val userIdsFuture = shoeboxServiceClient.getFriends(user1965.id.get)
        Await.result(userIdsFuture, Duration(5, SECONDS)) === Set(1933, 1935, 1927, 1921).map(Id[User](_))
      }
    }

    "get phrases by page" in {
      running(new CommonTestApplication(shoeboxServiceClientTestModules: _*)) {
        val shoeboxServiceClient = inject[ShoeboxServiceClient]
        val phrasesFuture = shoeboxServiceClient.getPhrasesChanged(SequenceNumber(0), 4)
        Await.result(phrasesFuture, Duration(5, SECONDS)) === phrases

      }
    }

  }
}
