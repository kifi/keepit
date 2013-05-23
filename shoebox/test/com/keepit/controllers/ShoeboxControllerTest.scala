package com.keepit.controllers

import org.specs2.mutable.Specification
import play.api.test.Helpers._
import com.keepit.common.db.slick._
import com.keepit.test.{DbRepos, EmptyApplication}
import com.keepit.inject._
import com.keepit.model._
import play.api.Play.current
import play.api.libs.json.{JsNumber, JsArray}
import com.keepit.serializer.UserSerializer
import com.keepit.model.User
import com.keepit.controllers.shoebox.ShoeboxController
import play.api.test.FakeRequest

class ShoeboxControllerTest extends Specification with DbRepos {

  def setup() = {
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

  "ShoeboxController" should {

    "return users from the database" in {
        running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule().withFakeHttpClient()) {
          val (user1965,friends) = setup()
          val users = user1965::friends
          val shoeboxController = inject[ShoeboxController]
          val query = users.map(_.id.get).mkString(",")
          val result = shoeboxController.getUsers(query)(FakeRequest())
          status(result) must equalTo(OK);
          contentType(result) must beSome("application/json");
          contentAsString(result) must equalTo(JsArray(users.map(UserSerializer.userSerializer.writes)).toString())
        }
    }



    "return connected users' ids from the database" in {
      running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule().withFakeHttpClient()) {
        val (user1965,friends) = setup()
        val shoeboxController = inject[ShoeboxController]
        val result = shoeboxController.getConnectedUsers(user1965.id.get)(FakeRequest())
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");
        contentAsString(result) must equalTo(JsArray(friends.map(friend => JsNumber(friend.id.get.id))).toString())
      }
    }

  }

}
