package com.keepit.controllers.website

import org.specs2.mutable.Specification


import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.controller._
import com.keepit.common.db.slick.Database
import com.keepit.inject.ApplicationInjector
import com.keepit.model._
import com.keepit.social.{SocialId, SocialNetworks}
import com.keepit.test.ShoeboxApplication

import play.api.libs.json.{JsObject, Json}
import play.api.mvc.SimpleResult
import play.api.test.Helpers._
import play.api.test._
import securesocial.core._
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.abook.TestABookServiceClientModule
import com.keepit.common.mail.TestMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{FakeShoeboxSecureSocialModule, FakeSocialGraphModule}
import com.keepit.search.TestSearchServiceClientModule
import com.keepit.scraper.{TestScraperServiceClientModule, FakeScrapeSchedulerModule}

import scala.concurrent.Future
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.cortex.FakeCortexServiceClientModule

class UserControllerTest extends Specification with ApplicationInjector {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    TestSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    ShoeboxFakeStoreModule(),
    TestActorSystemModule(),
    FakeAirbrakeModule(),
    TestABookServiceClientModule(),
    TestMailModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    TestHeimdalServiceClientModule(),
    FakeShoeboxSecureSocialModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    TestScraperServiceClientModule()
  )

  "UserController" should {

    "get currentUser" in {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
        val user = inject[Database].readWrite { implicit session =>
          val user = inject[UserRepo].save(User(firstName = "Shanee", lastName = "Smith"))
          inject[UserExperimentRepo].save(UserExperiment(userId = user.id.get, experimentType = ExperimentType.ADMIN))
          user
        }

        val path = com.keepit.controllers.website.routes.UserController.currentUser().toString
        path === "/site/user/me"

        inject[FakeActionAuthenticator].setUser(user, Set(ExperimentType.ADMIN))

        val request = FakeRequest("GET", path)
        val result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
            {
              "id":"${user.externalId}",
              "firstName":"Shanee",
              "lastName":"Smith",
              "pictureName":"0.jpg",
              "emails":[],
              "notAuthed":[],
              "experiments":["admin"],
              "uniqueKeepsClicked":0,
              "totalKeepsClicked":0,
              "clickCount":-1,
              "rekeepCount":-1
            }
          """)

        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "fetch my social connections, in the proper order" in {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
        val user = inject[Database].readWrite { implicit session =>

          val userRepo = inject[UserRepo]
          val socialConnectionRepo = inject[SocialConnectionRepo]
          val socialuserInfoRepo = inject[SocialUserInfoRepo]

          val user1 = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
          val user2 = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))

          val creds = SocialUser(IdentityId("asdf", "facebook"),
          "Eishay", "Smith", "Eishay Smith", None, None, AuthenticationMethod.OAuth2, None, None)

          val su1 = socialuserInfoRepo.save(SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("asdf"),
            networkType = SocialNetworks.FACEBOOK, userId = user1.id, credentials = Some(creds)))
          val su2 = socialuserInfoRepo.save(SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("aoeu"),
            networkType = SocialNetworks.LINKEDIN, userId = user1.id))
          val su3 = socialuserInfoRepo.save(SocialUserInfo(fullName = "Léo Grimaldi", socialId = SocialId("arst"),
            networkType = SocialNetworks.FACEBOOK, userId = None))
          val su4 = socialuserInfoRepo.save(SocialUserInfo(fullName = "Andrew Conner", socialId = SocialId("abcd"),
            networkType = SocialNetworks.LINKEDIN, userId = user2.id))
          val su5 = socialuserInfoRepo.save(SocialUserInfo(fullName = "杨莹", socialId = SocialId("defg"),
            networkType = SocialNetworks.LINKEDIN, userId = user2.id))


          socialConnectionRepo.save(SocialConnection(socialUser1 = su1.id.get, socialUser2 = su3.id.get))
          socialConnectionRepo.save(SocialConnection(socialUser1 = su2.id.get, socialUser2 = su4.id.get))
          socialConnectionRepo.save(SocialConnection(socialUser1 = su2.id.get, socialUser2 = su5.id.get))

          user1
        }

        def getNames(result: Future[SimpleResult]): Seq[String] = {
          Json.fromJson[Seq[JsObject]](Json.parse(contentAsString(result))).get.map(j => (j \ "label").as[String])
        }

        inject[FakeActionAuthenticator].setUser(user, Set(ExperimentType.ADMIN))

        {
          val path = com.keepit.controllers.website.routes.UserController.getAllConnections(Some("leo"), None, None, 10).toString
          path === "/site/user/socialConnections?search=leo&limit=10"
          val res = route(FakeRequest("GET", path)).get
          getNames(res) must_== Seq("Léo Grimaldi")
        }
        {
          val path = com.keepit.controllers.website.routes.UserController.getAllConnections(Some("杨"), None, None, 10).toString
          path === "/site/user/socialConnections?search=%E6%9D%A8&limit=10"
          val res = route(FakeRequest("GET", path)).get
          getNames(res) must_== Seq("杨莹")
        }
        {
          val path = com.keepit.controllers.website.routes.UserController.getAllConnections(None, None, None, 10).toString
          path === "/site/user/socialConnections?limit=10"
          val res = route(FakeRequest("GET", path)).get
          getNames(res) must_== Seq("Andrew Conner", "Léo Grimaldi", "杨莹")
        }
        {
          val path = com.keepit.controllers.website.routes.UserController.getAllConnections(Some("leo"), Some("facebook"), None, 2).toString
          path === "/site/user/socialConnections?search=leo&network=facebook&limit=2"
          val res = route(FakeRequest("GET", path)).get
          getNames(res) must_== Seq("Léo Grimaldi")
        }
        {
          val path = com.keepit.controllers.website.routes.UserController.getAllConnections(None, None, Some("facebook/arst"), 2).toString
          path === "/site/user/socialConnections?after=facebook%2Farst&limit=2"
          val res = route(FakeRequest("GET", path)).get
          getNames(res) must_== Seq("杨莹")
        }
      }
    }
  }
}
