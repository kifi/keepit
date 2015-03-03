package com.keepit.controllers.website

import com.keepit.commanders.{ FriendStatusCommander, UserConnectionsCommander }
import com.keepit.common.time._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.KeepFactory._
import com.keepit.model.LibraryInviteFactory._
import com.keepit.model.LibraryInviteFactoryHelper._
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
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database

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
import com.keepit.social.BasicUser
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsNull, Json }
import play.api.mvc.Result
import play.api.test.Helpers._
import play.api.test._

import scala.concurrent.Future
import scala.slick.jdbc.StaticQuery

class UserControllerTest extends Specification with ShoeboxTestInjector {

  val controllerTestModules = Seq(
    FakeClockModule(),
    FakeShoeboxServiceModule(),
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeABookServiceClientModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
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
          "auto_show_persona" -> JsNull,
          "library_sorting_pref" -> "name",
          "show_delighted_question" -> false,
          "site_introduce_library_menu" -> JsNull,
          "has_no_password" -> JsNull)
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
        val (userGW, userAL, userTJ, userJA, userBF) = db.readWrite { implicit session =>
          val userGW = userRepo.save(User(firstName = "George", lastName = "Washington", username = Username("GDubs"), normalizedUsername = "gdubs"))
          val userAL = userRepo.save(User(firstName = "Abe", lastName = "Lincoln", username = Username("abe"), normalizedUsername = "abe"))
          val userTJ = userRepo.save(User(firstName = "Thomas", lastName = "Jefferson", username = Username("TJ"), normalizedUsername = "tj"))
          val userJA = userRepo.save(User(firstName = "John", lastName = "Adams", username = Username("jayjayadams"), normalizedUsername = "jayjayadams"))
          val userBF = userRepo.save(User(firstName = "Ben", lastName = "Franklin", username = Username("Benji"), normalizedUsername = "benji"))
          val userConnectionRepo = inject[UserConnectionRepo]
          val now = new DateTime(2013, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
          userConnectionRepo.save(UserConnection(user1 = userGW.id.get, user2 = userAL.id.get, createdAt = now.plusDays(1)))
          userConnectionRepo.save(UserConnection(user1 = userGW.id.get, user2 = userTJ.id.get, createdAt = now.plusDays(2)))
          userConnectionRepo.save(UserConnection(user1 = userJA.id.get, user2 = userGW.id.get, createdAt = now.plusDays(3)))
          (userGW, userAL, userTJ, userJA, userBF)
        }
        val userController = inject[UserController]

        val connections = inject[UserConnectionsCommander].getConnectionsPage(userGW.id.get, 0, 5)._1
        connections.map(_.userId) === Seq(userJA.id.get, userTJ.id.get, userAL.id.get)

        inject[FakeUserActionsHelper].setUser(userGW)
        val request1 = FakeRequest("GET", routes.UserController.friends().url)
        val result1: Future[Result] = userController.friends(0, 5)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        val resultJson = contentAsJson(result1)
        val resultIds = (resultJson \\ "id").map(_.as[ExternalId[User]])
        resultIds === List(userJA.externalId, userTJ.externalId, userAL.externalId)
      }
    }

    "get profile connections" in {
      withDb(controllerTestModules: _*) { implicit injector =>
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

          val user3lib = library().withUser(user3).published().saved.savedFollowerMembership(user2)
          val user5lib = library().withUser(user5).published().saved.savedFollowerMembership(user1)
          membership().withLibraryFollower(library().withUser(user5).published().saved, user1).unlisted().saved

          invite().fromLibraryOwner(user3lib).toUser(user1.id.get).withState(LibraryInviteStates.ACTIVE).saved
          invite().fromLibraryOwner(user3lib).toUser(user1.id.get).withState(LibraryInviteStates.ACTIVE).saved // duplicate library invite
          invite().fromLibraryOwner(user5lib).toUser(user1.id.get).withState(LibraryInviteStates.ACCEPTED).saved

          keeps(2).map(_.withLibrary(user1secretLib)).saved
          keeps(3).map(_.withLibrary(user1lib)).saved
          keep().withLibrary(user3lib).saved

          (user1, user2, user3, user4, user5, user1lib)
        }
        val controller = inject[UserController]
        def basicUserWFS(user: User, viewerIdOpt: Option[Id[User]]): BasicUserWithFriendStatus = {
          val basicUser = BasicUser.fromUser(user)
          viewerIdOpt map { viewerId =>
            db.readOnlyMaster { implicit s =>
              inject[FriendStatusCommander].augmentUser(viewerId, user.id.get, basicUser)
            }
          } getOrElse BasicUserWithFriendStatus.fromWithoutFriendStatus(basicUser)
        }
        controller.loadFullConnectionUser(user1.id.get, basicUserWFS(user1, user2.id), user2.id) === Json.obj(
          "id" -> user1.externalId,
          "firstName" -> user1.firstName,
          "lastName" -> user1.lastName,
          "pictureName" -> "pic1.jpg",
          "username" -> user1.username.value,
          "isFriend" -> true,
          "libraries" -> 2, "connections" -> 3, "followers" -> 3,
          "mLibraries" -> 0, "mConnections" -> 1
        )
        controller.loadFullConnectionUser(user1.id.get, basicUserWFS(user1, None), None) === Json.obj(
          "id" -> user1.externalId,
          "firstName" -> user1.firstName,
          "lastName" -> user1.lastName,
          "pictureName" -> "pic1.jpg",
          "username" -> user1.username.value,
          "libraries" -> 1,
          "connections" -> 3, "followers" -> 2
        )
        controller.loadFullConnectionUser(user2.id.get, basicUserWFS(user2, user3.id), user3.id) === Json.obj(
          "id" -> user2.externalId,
          "firstName" -> user2.firstName,
          "lastName" -> user2.lastName,
          "pictureName" -> "0.jpg",
          "username" -> user2.username.value,
          "isFriend" -> true,
          "libraries" -> 0, "connections" -> 2, "followers" -> 0,
          "mLibraries" -> 1, "mConnections" -> 1
        )
        controller.loadFullConnectionUser(user2.id.get, basicUserWFS(user2, None), None) === Json.obj(
          "id" -> user2.externalId,
          "firstName" -> user2.firstName,
          "lastName" -> user2.lastName,
          "pictureName" -> "0.jpg",
          "username" -> user2.username.value,
          "libraries" -> 0, "connections" -> 2, "followers" -> 0
        )
      }
    }

    "get profile libraries" in {
      withDb(controllerTestModules: _*) { implicit injector =>
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

          invite().fromLibraryOwner(user3lib).toUser(user1.id.get).withState(LibraryInviteStates.ACTIVE).saved
          invite().fromLibraryOwner(user3lib).toUser(user1.id.get).withState(LibraryInviteStates.ACTIVE).saved // duplicate library invite
          invite().fromLibraryOwner(user5lib).toUser(user1.id.get).withState(LibraryInviteStates.ACCEPTED).saved

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

          libraryMembershipRepo.countWithUserIdAndAccess(user1.id.get, LibraryAccess.OWNER) === 4
          libraryMembershipRepo.countWithUserIdAndAccess(user2.id.get, LibraryAccess.OWNER) === 0
          libraryMembershipRepo.countWithUserIdAndAccess(user3.id.get, LibraryAccess.OWNER) === 1
          libraryMembershipRepo.countWithUserIdAndAccess(user4.id.get, LibraryAccess.OWNER) === 0
          libraryMembershipRepo.countWithUserIdAndAccess(user5.id.get, LibraryAccess.OWNER) === 2

          libraryRepo.countLibrariesOfUserFromAnonymous(user1.id.get) === 1
          libraryRepo.countLibrariesOfUserFromAnonymous(user2.id.get) === 0
          libraryRepo.countLibrariesOfUserFromAnonymous(user3.id.get) === 1
          libraryRepo.countLibrariesOfUserFromAnonymous(user4.id.get) === 0
          libraryRepo.countLibrariesOfUserFromAnonymous(user5.id.get) === 2
          libraryRepo.countLibrariesForOtherUser(user1.id.get, user5.id.get) === 1
          libraryRepo.countLibrariesForOtherUser(user1.id.get, user2.id.get) === 2
          libraryRepo.countLibrariesForOtherUser(user2.id.get, user5.id.get) === 0
          libraryRepo.countLibrariesForOtherUser(user3.id.get, user5.id.get) === 1
          libraryRepo.countLibrariesForOtherUser(user4.id.get, user5.id.get) === 0
          libraryRepo.countLibrariesForOtherUser(user5.id.get, user1.id.get) === 2
        }
        val userController = inject[UserController]
        def call(viewer: Option[User], viewing: Username) = {
          viewer match {
            case None => inject[FakeUserActionsHelper].unsetUser()
            case Some(user) => inject[FakeUserActionsHelper].setUser(user)
          }
          val request = FakeRequest("GET", routes.UserController.profile(viewing).url)
          userController.profile(viewing)(request)
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
              "numLibraries": 1,
              "numKeeps": 5,
              "numConnections": 3,
              "numFollowers": 2
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
              "numLibraries": 4,
              "numKeeps": 5,
              "numConnections": 3,
              "numFollowers": 3,
              "numInvitedLibraries": 1
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
              "isFriend": true,
              "numLibraries": 2,
              "numKeeps": 5,
              "numConnections": 3,
              "numFollowers": 3
            }
          """)
      }
    }

    "get profile followers" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, user3, user4, user5) = db.readWrite { implicit session =>
          val user1 = user().withName("George", "Washington").withUsername("GDubs").withPictureName("pic1").saved
          val user2 = user().withName("Abe", "Lincoln").withUsername("abe").saved
          val user3 = user().withName("Thomas", "Jefferson").withUsername("TJ").saved
          val user4 = user().withName("John", "Adams").withUsername("jayjayadams").saved
          val user5 = user().withName("Ben", "Franklin").withUsername("Benji").saved

          libraries(3).map(_.withUser(user1).secret()).saved.head.savedFollowerMembership(user2, user3) // create 3 secret libraries, one is follower by user2 and user3
          library().withUser(user1).published().saved.savedFollowerMembership(user5, user4) // create 1 published library, followed by user4 and user 5

          (user1, user2, user3, user4, user5)
        }
        val controller = inject[UserController]

        // get all follower ids for owner
        inject[FakeUserActionsHelper].setUser(user1)
        val resultIds = controller.profileFollowerIds(Username("GDubs"), 100)(FakeRequest("GET", routes.UserController.profileFollowerIds(Username("GDubs"), 100).url))
        status(resultIds) must equalTo(OK)
        contentType(resultIds) must beSome("application/json")
        Json.parse(contentAsString(resultIds)) === Json.parse(s"""
           {
            "ids" : ["${user2.externalId}", "${user3.externalId}", "${user4.externalId}", "${user5.externalId}"]
           }
         """
        )

        // view as owner
        inject[FakeUserActionsHelper].setUser(user1)
        val result1 = controller.profileFollowers(Username("GDubs"), 10, "")(FakeRequest("GET", routes.UserController.profileFollowers(Username("GDubs"), 10).url))
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        val resultJson1 = contentAsJson(result1)
        (resultJson1 \\ "count").map(_.as[Int] === 4)
        (resultJson1 \\ "id").map(_.as[ExternalId[User]]) === Seq(user2.externalId, user3.externalId, user4.externalId, user5.externalId)

        // view as anybody
        inject[FakeUserActionsHelper].setUser(user4)
        val result2 = controller.profileFollowers(Username("GDubs"), 10, "")(FakeRequest("GET", routes.UserController.profileFollowers(Username("GDubs"), 10).url))
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        val resultJson2 = contentAsJson(result2)
        (resultJson2 \\ "count").map(_.as[Int] === 2)
        (resultJson2 \\ "id").map(_.as[ExternalId[User]]) === Seq(user5.externalId, user4.externalId)

        // view as follower (to a secret library)
        inject[FakeUserActionsHelper].setUser(user2)
        val result3 = controller.profileFollowers(Username("GDubs"), 10, "")(FakeRequest("GET", routes.UserController.profileFollowers(Username("GDubs"), 10).url))
        status(result3) must equalTo(OK)
        contentType(result3) must beSome("application/json")
        val resultJson3 = contentAsJson(result3)
        (resultJson3 \\ "count").map(_.as[Int] === 3)
        (resultJson3 \\ "id").map(_.as[ExternalId[User]]) === Seq(user4.externalId, user5.externalId, user2.externalId)

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
