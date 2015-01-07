package com.keepit.controllers.website

import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.KeepFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserConnectionFactoryHelper._
import com.keepit.model.UserConnectionFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller._
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.oauth.FakeOAuth2ConfigurationModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsNull, Json }
import play.api.mvc.Result
import play.api.test.Helpers._
import play.api.test._

import scala.concurrent.Future
import scala.slick.jdbc.StaticQuery

class UserControllerTest extends Specification with ShoeboxTestInjector {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeABookServiceClientModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeCuratorServiceClientModule(),
    FakeOAuth2ConfigurationModule()
  )

  "UserController" should {

    "get currentUser" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          val user = UserFactory.user().withName("Shanee", "Smith").withUsername("test").saved
          inject[UserExperimentRepo].save(UserExperiment(userId = user.id.get, experimentType = ExperimentType.ADMIN))
          user
        }

        val path = routes.UserController.currentUser().url
        path === "/site/user/me"

        inject[FakeUserActionsHelper].setUser(user, Set(ExperimentType.ADMIN))

        val request = FakeRequest("GET", path)
        val result = inject[UserController].currentUser()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
            {
              "id":"${user.externalId}",
              "firstName":"Shanee",
              "lastName":"Smith",
              "pictureName":"0.jpg",
              "username":"test",
              "emails":[],
              "notAuthed":[],
              "experiments":["admin", "libraries"],
              "uniqueKeepsClicked":0,
              "totalKeepsClicked":0,
              "clickCount":0,
              "rekeepCount":0,
              "rekeepTotalCount":0
            }
          """)

        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "update username" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          val user = inject[UserRepo].save(User(firstName = "George", lastName = "Washington", username = Username("GeorgeWash"), normalizedUsername = "foo"))
          inject[UserExperimentRepo].save(UserExperiment(userId = user.id.get, experimentType = ExperimentType.ADMIN))
          user
        }
        val path = routes.UserController.updateUsername().url
        path === "/site/user/me/username"

        inject[FakeUserActionsHelper].setUser(user, Set(ExperimentType.ADMIN))

        val inputJson1 = Json.obj(
          "username" -> "GDubs"
        )
        val request = FakeRequest("POST", path).withBody(inputJson1)
        val result: Future[Result] = inject[UserController].updateUsername()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val expected = Json.parse(s"""{"username":"GDubs"}""")
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "update user info" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "George", lastName = "Washington", username = Username("GeorgeWash"), normalizedUsername = "foo"))
        }
        val userController = inject[UserController]
        val pathName = routes.UserController.updateName().url
        val pathDescription = routes.UserController.updateDescription().url
        pathName === "/site/user/me/name"
        pathDescription === "/site/user/me/description"

        inject[FakeUserActionsHelper].setUser(user, Set(ExperimentType.ADMIN))

        val inputJson1 = Json.obj(
          "firstName" -> "Abe",
          "lastName" -> "Lincoln"
        )
        val request1 = FakeRequest("POST", pathName).withBody(inputJson1)
        val result1: Future[Result] = userController.updateName()(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        db.readOnlyMaster { implicit s =>
          val userCheck = userRepo.get(user.id.get)
          userCheck.firstName === "Abe"
          userCheck.lastName === "Lincoln"
        }

        val inputJson2 = Json.obj(
          "description" -> "USA #1"
        )
        val request2 = FakeRequest("POST", pathDescription).withBody(inputJson2)
        val result2: Future[Result] = userController.updateDescription()(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        db.readOnlyMaster { implicit s =>
          val userCheck = inject[UserValueRepo].getUserValue(user.id.get, UserValueName.USER_DESCRIPTION)
          userCheck.get.value === "USA #1"
        }
      }
    }

    "update user preferences" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "George", lastName = "Washington", username = Username("GeorgeWash"), normalizedUsername = "foo"))
        }

        inject[FakeUserActionsHelper].setUser(user)
        val userController = inject[UserController]
        val path = routes.UserController.savePrefs().url

        val inputJson1 = Json.obj(
          "library_sorting_pref" -> "name",
          "show_delighted_question" -> false)
        val request1 = FakeRequest("POST", path).withBody(inputJson1)
        val result1: Future[Result] = userController.savePrefs()(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        Json.parse(contentAsString(result1)) === inputJson1

        db.readOnlyMaster { implicit s =>
          inject[UserValueRepo].getValueStringOpt(user.id.get, UserValueName.LIBRARY_SORTING_PREF) === Some("name")
        }

        val request2 = FakeRequest("GET", path)
        val result2: Future[Result] = userController.getPrefs()(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        Json.parse(contentAsString(result2)) === Json.obj(
          "auto_show_guide" -> JsNull,
          "library_sorting_pref" -> "name",
          "show_delighted_question" -> false,
          "library_callout_shown" -> JsNull,
          "tag_callout_shown" -> JsNull,
          "guide_callout_shown" -> JsNull,
          "site_show_library_intro" -> JsNull)
      }
    }

    "handling emails" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Abe", lastName = "Lincoln", username = Username("AbeLincoln"), normalizedUsername = "foo"))
        }
        val userController = inject[UserController]
        val userValueRepo = inject[UserValueRepo]
        val path = routes.UserController.addEmail().url
        path === "/site/user/me/email"

        val address1 = "vampireXslayer@gmail.com"
        val address2 = "uncleabe@gmail.com"

        val inputJson1 = Json.obj(
          "email" -> address1,
          "isPrimary" -> false
        )
        val inputJson2 = Json.obj(
          "email" -> address2,
          "isPrimary" -> true
        )

        inject[FakeUserActionsHelper].setUser(user)

        // add email1
        val request1 = FakeRequest("POST", path).withBody(inputJson1)
        val result1: Future[Result] = userController.addEmail()(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        // add email2 as primary
        val request2 = FakeRequest("POST", path).withBody(inputJson2)
        val result2: Future[Result] = userController.addEmail()(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        // add email2 again (but already added)
        val request3 = FakeRequest("POST", path).withBody(inputJson2)
        val result3: Future[Result] = userController.addEmail()(request3)
        status(result3) must equalTo(BAD_REQUEST)

        // verify emails
        db.readWrite { implicit session =>
          emailAddressRepo.getAllByUser(user.id.get).map { em =>
            emailAddressRepo.save(em.copy(state = UserEmailAddressStates.VERIFIED))
          }
          userRepo.save(user.copy(primaryEmail = Some(EmailAddress(address2)))) // because email2 is pending primary
          userValueRepo.clearValue(user.id.get, UserValueName.PENDING_PRIMARY_EMAIL)
        }

        // change primary to email1
        val request4 = FakeRequest("PUT", path).withBody(Json.obj("email" -> address1))
        val result4: Future[Result] = userController.changePrimaryEmail()(request4)
        status(result4) must equalTo(OK)
        contentType(result4) must beSome("application/json")

        // remove email2
        val request5 = FakeRequest("DELETE", path).withBody(Json.obj("email" -> address2))
        val result5: Future[Result] = userController.removeEmail()(request5)
        status(result5) must equalTo(OK)
        contentType(result5) must beSome("application/json")

        // remove email1 (but can't since it's primary)
        val request6 = FakeRequest("DELETE", path).withBody(Json.obj("email" -> address1))
        val result6: Future[Result] = userController.removeEmail()(request6)
        status(result6) must equalTo(BAD_REQUEST) // cannot delete primary email
      }
    }

    "get friends" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val userConnectionRepo = inject[UserConnectionRepo]
        val (userGW, userAL, userTJ, userJA, userBF) = db.readWrite { implicit session =>
          val userGW = userRepo.save(User(firstName = "George", lastName = "Washington", username = Username("GDubs"), normalizedUsername = "gdubs"))
          val userAL = userRepo.save(User(firstName = "Abe", lastName = "Lincoln", username = Username("abe"), normalizedUsername = "abe"))
          val userTJ = userRepo.save(User(firstName = "Thomas", lastName = "Jefferson", username = Username("TJ"), normalizedUsername = "tj"))
          val userJA = userRepo.save(User(firstName = "John", lastName = "Adams", username = Username("jayjayadams"), normalizedUsername = "jayjayadams"))
          val userBF = userRepo.save(User(firstName = "Ben", lastName = "Franklin", username = Username("Benji"), normalizedUsername = "benji"))
          userConnectionRepo.save(UserConnection(user1 = userGW.id.get, user2 = userAL.id.get))
          userConnectionRepo.save(UserConnection(user1 = userGW.id.get, user2 = userTJ.id.get))
          userConnectionRepo.save(UserConnection(user1 = userJA.id.get, user2 = userGW.id.get))
          (userGW, userAL, userTJ, userJA, userBF)
        }
        val userController = inject[UserController]

        inject[FakeUserActionsHelper].setUser(userGW)
        val request1 = FakeRequest("GET", routes.UserController.friends().url)
        val result1: Future[Result] = userController.friends(0, 5)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        val resultJson = contentAsJson(result1)
        val resultIds = (resultJson \\ "id").map(_.as[ExternalId[User]])
        resultIds === List(userAL.externalId, userTJ.externalId, userJA.externalId)
      }
    }

    "get profile for self" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val userConnectionRepo = inject[UserConnectionRepo]
        val (user1, user2, user3, user4, user5, lib1) = db.readWrite { implicit session =>
          val user1 = user().withName("George", "Washington").withUsername("GDubs").withPictureName("pic1").saved
          val user2 = user().withName("Abe", "Lincoln").withUsername("abe").saved
          val user3 = user().withName("Thomas", "Jefferson").withUsername("TJ").saved
          val user4 = user().withName("John", "Adams").withUsername("jayjayadams").saved
          val user5 = user().withName("Ben", "Franklin").withUsername("Benji").saved

          connect(user1 -> user2,
            user1 -> user3,
            user4 -> user1,
            user2 -> user3).saved

          val user1secretLib = libraries(3).map(_.withUser(user1).secret()).saved.head.savedFollowerMembership(user2)

          val user1lib = library().withUser(user1).published().saved.savedFollowerMembership(user5, user4)
          user1lib.visibility === LibraryVisibility.PUBLISHED

          val user3lib = library().withUser(user3).published().saved
          val user5lib = library().withUser(user5).published().saved.savedFollowerMembership(user1)
          membership().withLibraryFollower(library().withUser(user5).published().saved, user1).unlisted().saved

          keeps(2).map(_.withLibrary(user1secretLib)).saved
          keeps(3).map(_.withLibrary(user1lib)).saved
          keep().withLibrary(user3lib).saved

          (user1, user2, user3, user4, user5, user1lib)
        }
        db.readOnlyMaster { implicit s =>
          val libMem = libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user4.id.get).get
          libMem.access === LibraryAccess.READ_ONLY
          libMem.state.value === "active"

          import StaticQuery.interpolation
          val ret1 = sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = 4".as[Int].firstOption.getOrElse(0)
          ret1 === 1
          val ret2 = sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = 4 and lib.state = 'active' and lm.state = 'active' and lm.listed and lib.visibility = 'published'".as[Int].firstOption.getOrElse(0)
          ret2 === 1

          libraryRepo.countLibrariesToSelf(user1.id.get) === 6
          libraryRepo.countLibrariesToSelf(user2.id.get) === 1
          libraryRepo.countLibrariesToSelf(user3.id.get) === 1
          libraryRepo.countLibrariesToSelf(user4.id.get) === 1
          libraryRepo.countLibrariesToSelf(user5.id.get) === 3

          libraryRepo.countLibrariesOfUserFromAnonymous(user1.id.get, countFollowLibraries = true) === 2
          libraryRepo.countLibrariesOfUserFromAnonymous(user1.id.get, countFollowLibraries = false) === 1

          libraryRepo.countLibrariesOfUserFromAnonymous(user2.id.get, countFollowLibraries = true) === 0
          libraryRepo.countLibrariesOfUserFromAnonymous(user2.id.get, countFollowLibraries = false) === 0

          libraryRepo.countLibrariesOfUserFromAnonymous(user3.id.get, countFollowLibraries = true) === 1
          libraryRepo.countLibrariesOfUserFromAnonymous(user3.id.get, countFollowLibraries = false) === 1

          libraryRepo.countLibrariesOfUserFromAnonymous(user4.id.get, countFollowLibraries = true) === 1
          libraryRepo.countLibrariesOfUserFromAnonymous(user4.id.get, countFollowLibraries = false) === 0

          libraryRepo.countLibrariesOfUserFromAnonymous(user5.id.get, countFollowLibraries = true) === 3
          libraryRepo.countLibrariesOfUserFromAnonymous(user5.id.get, countFollowLibraries = false) === 2

          libraryRepo.countLibrariesForOtherUser(user1.id.get, user5.id.get, countFollowLibraries = true) === 2
          libraryRepo.countLibrariesForOtherUser(user1.id.get, user5.id.get, countFollowLibraries = false) === 1

          libraryRepo.countLibrariesForOtherUser(user1.id.get, user2.id.get, countFollowLibraries = true) === 3
          libraryRepo.countLibrariesForOtherUser(user1.id.get, user2.id.get, countFollowLibraries = false) === 2

          libraryRepo.countLibrariesForOtherUser(user2.id.get, user5.id.get, countFollowLibraries = true) === 0
          libraryRepo.countLibrariesForOtherUser(user2.id.get, user5.id.get, countFollowLibraries = false) === 0

          libraryRepo.countLibrariesForOtherUser(user3.id.get, user5.id.get, countFollowLibraries = true) === 1
          libraryRepo.countLibrariesForOtherUser(user3.id.get, user5.id.get, countFollowLibraries = false) === 1

          libraryRepo.countLibrariesForOtherUser(user4.id.get, user5.id.get, countFollowLibraries = true) === 1
          libraryRepo.countLibrariesForOtherUser(user4.id.get, user5.id.get, countFollowLibraries = false) === 0

          libraryRepo.countLibrariesForOtherUser(user5.id.get, user1.id.get, countFollowLibraries = true) === 3
          libraryRepo.countLibrariesForOtherUser(user5.id.get, user1.id.get, countFollowLibraries = false) === 2
        }
        val userController = inject[UserController]
        def call(viewer: Option[User], viewing: Username) = {
          viewer match {
            case None => inject[FakeUserActionsHelper].unsetUser()
            case Some(user) => inject[FakeUserActionsHelper].setUser(user)
          }
          val request = FakeRequest("GET", routes.UserController.profile(viewing.value).url)
          userController.profile(viewing.value)(request)
        }
        //non existing username
        status(call(Some(user1), Username("foo"))) must equalTo(NOT_FOUND)

        //seeing a profile from an anonymos user
        val anonViewer = call(None, user1.username)
        status(anonViewer) must equalTo(OK)
        contentType(anonViewer) must beSome("application/json")
        contentAsJson(anonViewer) === Json.parse(
          s"""
            {
              "id":"${user1.externalId.id}",
              "firstName":"George",
              "lastName":"Washington",
              "pictureName":"pic1.jpg",
              "username": "GDubs",
              "numLibraries": 2,
              "numKeeps": 5
            }
          """)

        //seeing a profile of my own
        val selfViewer = call(Some(user1), user1.username)
        status(selfViewer) must equalTo(OK)
        contentType(selfViewer) must beSome("application/json")
        val res2 = contentAsJson(selfViewer)
        res2 === Json.parse(
          s"""
            {
              "id":"${user1.externalId.id}",
              "firstName":"George",
              "lastName":"Washington",
              "pictureName":"pic1.jpg",
              "username": "GDubs",
              "numLibraries": 6,
              "numKeeps": 5
            }
          """)

        //seeing a profile from another user (friend)
        val friendViewer = call(Some(user2), user1.username)
        status(friendViewer) must equalTo(OK)
        contentType(friendViewer) must beSome("application/json")
        val res3 = contentAsJson(friendViewer)
        res3 === Json.parse(
          s"""
            {
              "id":"${user1.externalId.id}",
              "firstName":"George",
              "lastName":"Washington",
              "pictureName":"pic1.jpg",
              "username": "GDubs",
              "numLibraries": 3,
              "numKeeps": 5,
              "isFriend": true
            }
          """)
      }
    }

    "set user profile settings" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "George", lastName = "Washington", username = Username("GDubs"), normalizedUsername = "gdubs"))
        }

        inject[FakeUserActionsHelper].setUser(user)
        val userController = inject[UserController]
        val getPath = routes.UserController.getSettings().url
        val setPath = routes.UserController.setSettings().url

        // initial getSettings
        val request1 = FakeRequest("GET", getPath)
        val result1: Future[Result] = userController.getSettings()(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        contentAsString(result1) === s"""{"showFollowedLibraries":true}"""

        // set settings (showFollowedLibraries to false)
        val request2 = FakeRequest("POST", setPath).withBody(Json.obj("showFollowedLibraries" -> false))
        val result2: Future[Result] = userController.setSettings()(request2)
        status(result2) must equalTo(NO_CONTENT)

        db.readOnlyMaster { implicit s =>
          inject[UserValueRepo].getValueStringOpt(user.id.get, UserValueName.USER_PROFILE_SETTINGS).get === s"""{"show_followed_libraries":false}"""
        }

        // get settings
        val request3 = FakeRequest("GET", getPath)
        val result3: Future[Result] = userController.getSettings()(request3)
        status(result3) must equalTo(OK)
        contentAsString(result3) === s"""{"showFollowedLibraries":false}"""

        // reset settings (showFollowedLibraries to true)
        val request4 = FakeRequest("POST", setPath).withBody(Json.obj("showFollowedLibraries" -> true))
        val result4: Future[Result] = userController.setSettings()(request4)
        status(result4) must equalTo(NO_CONTENT)

        db.readOnlyMaster { implicit s =>
          inject[UserValueRepo].getValueStringOpt(user.id.get, UserValueName.USER_PROFILE_SETTINGS).get === s"""{"show_followed_libraries":true}"""
        }
      }
    }

    "basicUserInfo" should {
      "return user info when found" in {
        withDb(controllerTestModules: _*) { implicit injector =>

          val user = inject[Database].readWrite { implicit rw =>
            inject[UserRepo].save(User(firstName = "Donald", lastName = "Trump", username = Username("test"), normalizedUsername = "test"))
          }

          inject[FakeUserActionsHelper].setUser(user)

          val controller = inject[UserController] // setup
          val result = controller.basicUserInfo(user.externalId, true)(FakeRequest())
          var body: String = contentAsString(result)

          contentType(result).get must beEqualTo("application/json")
          body must contain("id\":\"" + user.externalId)
          body must contain("firstName\":\"Donald")
          body must contain("lastName\":\"Trump")
          body must contain("friendCount\":0")
        }
      }
    }
  }
}
