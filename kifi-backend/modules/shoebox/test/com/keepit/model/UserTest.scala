package com.keepit.model

import com.keepit.commanders.HandleOps
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.healthcheck.FakeAirbrakeNotifier

import scala.util.Try

import play.api.libs.json.{ Json, JsValue }

import org.specs2.mutable._

import org.joda.time.DateTime

import com.keepit.common.time._
import com.keepit.common.db._
import com.keepit.test._

class UserTest extends Specification with ShoeboxTestInjector {

  "UsernameOps" should {
    "valid" in {
      HandleOps.isValid("eishay-kifi") === false
      HandleOps.isValid("nada-boutros") === false
      //the following line takes more then a minute to run!
      //UsernameOps.isValid("eishaytestwithaveryveryveryveryveryveryveryverylongmailgocom-") === false
      HandleOps.isValid("eishaytestwitha-") === false
    }
  }

  "UserRepo" should {
    "Update username" in {
      withDb(FakeExecutionContextModule()) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Andrew", lastName = "Conner", username = Username("test"), normalizedUsername = "test"))
        }
        inject[FakeAirbrakeNotifier].errorCount() === 0
        db.readWrite { implicit session =>
          userRepo.save(user.copy(username = Username("foobar")))
        }
        inject[FakeAirbrakeNotifier].errorCount() === 0
      }
    }
    "Use the cache" in {
      withDb(FakeExecutionContextModule()) { implicit injector =>
        val userRepoImpl = userRepo.asInstanceOf[UserRepoImpl]
        val user = db.readWrite { implicit session =>
          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).isDefined === false
          userRepo.save(User(firstName = "Andrew", lastName = "Conner", username = Username("test"), normalizedUsername = "test"))
        }
        val updatedUser = db.readWrite { implicit session =>
          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).get === user
          userRepo.save(user.copy(lastName = "NotMyLastName"))
        }
        db.readOnlyMaster { implicit session =>
          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).get !== user
          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).get === updatedUser
        }

        sessionProvider.doWithoutCreatingSessions {
          db.readOnlyMaster { implicit s => userRepoImpl.get(Id[User](1)) }
        }
        Try(db.readOnlyMaster { implicit s => userRepoImpl.get(Id[User](2)) })
        sessionProvider.readOnlySessionsCreated === 1
      }
    }

    "Distinguish real and fake users" in {
      withDb(FakeExecutionContextModule()) { implicit injector =>
        val userRepoImpl = userRepo.asInstanceOf[UserRepoImpl]

        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Martin", lastName = "Raison", username = Username("test"), normalizedUsername = "test"))
        }

        db.readOnlyMaster { implicit session =>
          userRepoImpl.pageIncludingWithoutExp(UserStates.ACTIVE)(ExperimentType.FAKE)().head === user
          userRepoImpl.pageIncludingWithExp(UserStates.ACTIVE)(ExperimentType.FAKE)().length === 0
          userRepoImpl.countIncludingWithoutExp(UserStates.ACTIVE)(ExperimentType.FAKE) === 1
          userRepoImpl.countIncludingWithExp(UserStates.ACTIVE)(ExperimentType.FAKE) === 0
        }

        db.readWrite { implicit session =>
          userExperimentRepo.save(UserExperiment(userId = user.id.get, experimentType = ExperimentType.FAKE))
        }

        db.readOnlyMaster { implicit session =>
          val updatedUser = userRepo.get(user.id.get)
          userRepoImpl.pageIncludingWithoutExp(UserStates.ACTIVE)(ExperimentType.FAKE)().length === 0
          userRepoImpl.pageIncludingWithExp(UserStates.ACTIVE)(ExperimentType.FAKE)().head === updatedUser
          userRepoImpl.countIncludingWithoutExp(UserStates.ACTIVE)(ExperimentType.FAKE) === 0
          userRepoImpl.countIncludingWithExp(UserStates.ACTIVE)(ExperimentType.FAKE) === 1
        }
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
        firstName = "Andrew", lastName = "Conner", username = Username("test"), normalizedUsername = "test")
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
          "seq":0,"username":"test","normalizedUsername":"test"}
        """)
    }
  }

}
