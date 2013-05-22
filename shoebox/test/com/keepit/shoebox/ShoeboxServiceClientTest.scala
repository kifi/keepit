package com.keepit.shoebox

import org.specs2.mutable.Specification
import play.api.test.Helpers._
import com.keepit.test.EmptyApplication
import com.keepit.common.net.{FakeHttpClient, HttpClient}
import com.keepit.inject._
import com.keepit.common.db.Id
import com.keepit.model._
import play.api.Play.current
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.json.JsArray
import com.keepit.serializer.UserSerializer

class ShoeboxServiceClientTest extends Specification {

  val user1965 = User(firstName="Richard",lastName="Feynman").withId(Id[User](1965))
  val user1933 = User(firstName="Paul",lastName="Dirac").withId(Id[User](1933))
  val users = Seq(user1965,user1933)

  def setup() = {
    new FortyTwoModule {
      override def configure() {
        bind[HttpClient].toInstance(new FakeHttpClient(Some({
          case s if s.contains("/internal/shoebox/database/getConnectedUsers") && s.contains("1965") => "[1933,1935,1927,1921]"
          case s if s.contains("/internal/shoebox/database/getUsers") && s.contains("1965%2C1933") => JsArray(users.map(UserSerializer.userSerializer.writes)).toString()

        })))
      }
    }
  }

  "ShoeboxServiceClient" should {

    "get users" in {
      running(new EmptyApplication().overrideWith(setup())) {
        val shoeboxServiceClient = inject[ShoeboxServiceClient]
        val usersFuture = shoeboxServiceClient.getUsers(users.map(_.id.get))
        Await.result(usersFuture, Duration(5, SECONDS)) ===  users

      }
    }

    "get connected users' ids" in {
      running(new EmptyApplication().overrideWith(setup())) {
        val shoeboxServiceClient = inject[ShoeboxServiceClient]
        val userIdsFuture = shoeboxServiceClient.getConnectedUsers(user1965.id.get)
        Await.result(userIdsFuture, Duration(5, SECONDS)) ===  Set(1933,1935,1927,1921).map(Id[User](_))

      }

    }

  }
}
