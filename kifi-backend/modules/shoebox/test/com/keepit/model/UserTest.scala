package com.keepit.model

import scala.util.Try

import play.api.libs.json.{Json, JsValue}

import org.specs2.mutable._

import org.joda.time.DateTime

import com.keepit.common.time._
import com.keepit.common.db._
import com.keepit.test._

class UserTest extends Specification with ShoeboxTestInjector {

  "UserRepo" should {
    "Use the cache" in {
      withDb() { implicit injector =>
        val userRepoImpl = userRepo.asInstanceOf[UserRepoImpl]
        db.readWrite { implicit session =>
          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).isDefined === false
          val user = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))

          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).get === user
          val updatedUser = userRepo.save(user.copy(lastName = "NotMyLastName"))

          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).get !== user
          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).get === updatedUser
        }

        sessionProvider.doWithoutCreatingSessions {
          db.readOnly { implicit s => userRepoImpl.get(Id[User](1)) }
        }
        Try(db.readOnly { implicit s => userRepoImpl.get(Id[User](2)) })
        sessionProvider.readOnlySessionsCreated === 1
      }
    }
  }

  "User" should {
    "serialize" in {
      val user = User(
        id = Some(Id[User](22)),
        externalId = ExternalId[User]("11ac839c-509e-400e-9111-3760433488ea"),
        updatedAt = new DateTime(2013, 3, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE),
        createdAt = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE),
        firstName = "Andrew", lastName = "Conner")
      val json = Json.toJson(user)
      json.as[User] === user
      json === Json.parse("""
        {
          "id":22,
          "createdAt":"2013-02-14T21:59:00.000Z",
          "updatedAt":"2013-03-14T21:59:00.000Z",
          "externalId":"11ac839c-509e-400e-9111-3760433488ea",
          "firstName":"Andrew",
          "lastName":"Conner",
          "state":"active",
          "seq":0}
        """)
    }
  }


}
