package com.keepit.controllers.website

import com.keepit.abook.TestABookServiceClientModule
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.controller._
import com.keepit.common.db.slick.Database
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.TestMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{ FakeShoeboxSecureSocialModule, FakeSocialGraphModule }
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.inject.ApplicationInjector
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, TestScraperServiceClientModule }
import com.keepit.search.{ FakeSearchServiceClientModule, TestSearchServiceClientModule }
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.{ SocialId, SocialNetworks }
import com.keepit.test.ShoeboxApplication
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsObject, Json }
import play.api.mvc.SimpleResult
import play.api.test.Helpers._
import play.api.test._
import securesocial.core._

import scala.concurrent.Future

class TypeaheadControllerTest extends Specification with ApplicationInjector {

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

  "TypeaheadController" should {

    "query connections" in {
      running(new ShoeboxApplication(controllerTestModules: _*)) {
        val user = inject[Database].readWrite { implicit session =>

          val userRepo = inject[UserRepo]
          val socialConnectionRepo = inject[SocialConnectionRepo]
          val socialUserInfoRepo = inject[SocialUserInfoRepo]

          val user1 = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
          val user2 = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))

          val creds = SocialUser(IdentityId("asdf", "facebook"),
            "Eishay", "Smith", "Eishay Smith", None, None, AuthenticationMethod.OAuth2, None, None)

          val su1 = socialUserInfoRepo.save(SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("asdf"),
            networkType = SocialNetworks.FACEBOOK, userId = user1.id, credentials = Some(creds)))
          val su2 = socialUserInfoRepo.save(SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("aoeu"),
            networkType = SocialNetworks.LINKEDIN, userId = user1.id))
          val su3 = socialUserInfoRepo.save(SocialUserInfo(fullName = "Léo Grimaldi", socialId = SocialId("arst"),
            networkType = SocialNetworks.FACEBOOK, userId = None))
          val su4 = socialUserInfoRepo.save(SocialUserInfo(fullName = "Andrew Conner", socialId = SocialId("abcd"),
            networkType = SocialNetworks.LINKEDIN, userId = user2.id))
          val su5 = socialUserInfoRepo.save(SocialUserInfo(fullName = "杨莹", socialId = SocialId("defg"),
            networkType = SocialNetworks.LINKEDIN, userId = user2.id))
          val su6 = socialUserInfoRepo.save(SocialUserInfo(fullName = "Raymond Ng", socialId = SocialId("rand"),
            networkType = SocialNetworks.FACEBOOK, userId = None))

          socialConnectionRepo.save(SocialConnection(socialUser1 = su1.id.get, socialUser2 = su3.id.get))
          socialConnectionRepo.save(SocialConnection(socialUser1 = su2.id.get, socialUser2 = su4.id.get))
          socialConnectionRepo.save(SocialConnection(socialUser1 = su2.id.get, socialUser2 = su5.id.get))
          socialConnectionRepo.save(SocialConnection(socialUser1 = su1.id.get, socialUser2 = su6.id.get))

          user1
        }

        def getNames(result: Future[SimpleResult]): Seq[String] = {
          Json.fromJson[Seq[JsObject]](Json.parse(contentAsString(result))).get.map(j => (j \ "label").as[String])
        }

        inject[FakeActionAuthenticator].setUser(user, Set(ExperimentType.ADMIN))

        {
          val path = com.keepit.controllers.website.routes.TypeaheadController.searchWithInviteStatus(Some("leo"), Some(10), false, false).toString
          path === "/site/user/connections/all/search?query=leo&limit=10&pictureUrl=false&dedupEmail=false"
          val res = route(FakeRequest("GET", path)).get
          getNames(res) must_== Seq("Léo Grimaldi")
        }
        {
          val path = com.keepit.controllers.website.routes.TypeaheadController.searchWithInviteStatus(Some("ray"), Some(10), false, false).toString
          path === "/site/user/connections/all/search?query=ray&limit=10&pictureUrl=false&dedupEmail=false"
          val res = route(FakeRequest("GET", path)).get
          println(s"content=${contentAsString(res)}")
          getNames(res) must_== Seq("Raymond Ng")
        }
        {
          val path = com.keepit.controllers.website.routes.TypeaheadController.searchWithInviteStatus(None, None, false, false).toString
          path === "/site/user/connections/all/search?pictureUrl=false&dedupEmail=false"
          val res = route(FakeRequest("GET", path)).get
          getNames(res) must_== Seq()
        }
        {
          val path = com.keepit.controllers.website.routes.TypeaheadController.searchWithInviteStatus(Some("leo"), Some(2), false, false).toString
          path === "/site/user/connections/all/search?query=leo&limit=2&pictureUrl=false&dedupEmail=false"
          val res = route(FakeRequest("GET", path)).get
          getNames(res) must_== Seq("Léo Grimaldi")
        }
      }
    }

  }
}
