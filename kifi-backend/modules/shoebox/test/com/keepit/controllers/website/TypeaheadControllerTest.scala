package com.keepit.controllers.website

import com.keepit.commanders.ConnectionWithInviteStatus
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller._
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.inject.ApplicationInjector
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
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

  val modules = Seq(
    FakeMailModule(),
    FakeActorSystemModule(),
    FakeShoeboxStoreModule(),
    FakeSearchServiceClientModule()
  )

  "TypeaheadController" should {

    "query connections" in {
      running(new ShoeboxApplication(modules: _*)) {
        val user = inject[Database].readWrite { implicit session =>

          val userRepo = inject[UserRepo]
          val socialConnectionRepo = inject[SocialConnectionRepo]
          val socialUserInfoRepo = inject[SocialUserInfoRepo]

          val user1 = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
          val user2 = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))

          val creds = SocialUser(IdentityId("id1", "facebook"),
            "Eishay", "Smith", "Eishay Smith", None, None, AuthenticationMethod.OAuth2, None, None)

          val su1 = socialUserInfoRepo.save(SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("id1"),
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
          val path = com.keepit.controllers.website.routes.TypeaheadController.searchWithInviteStatus(Some("leo"), Some(10), false, true).toString
          path === "/site/user/connections/all/search?query=leo&limit=10&pictureUrl=false"
          val res = route(FakeRequest("GET", path)).get
          getNames(res) must_== Seq("Léo Grimaldi")
        }
        {
          val path = com.keepit.controllers.website.routes.TypeaheadController.searchWithInviteStatus(Some("ray"), Some(10), false, true).toString
          path === "/site/user/connections/all/search?query=ray&limit=10&pictureUrl=false"
          val res = route(FakeRequest("GET", path)).get
          getNames(res) must_== Seq("Raymond Ng")
        }
        {
          val path = com.keepit.controllers.website.routes.TypeaheadController.searchWithInviteStatus(None, None, false, true).toString
          path === "/site/user/connections/all/search?pictureUrl=false"
          val res = route(FakeRequest("GET", path)).get
          getNames(res) must_== Seq()
        }
        {
          val path = com.keepit.controllers.website.routes.TypeaheadController.searchWithInviteStatus(Some("leo"), Some(2), false, true).toString
          path === "/site/user/connections/all/search?query=leo&limit=2&pictureUrl=false"
          val res = route(FakeRequest("GET", path)).get
          getNames(res) must_== Seq("Léo Grimaldi")
        }
      }
    }

    "query & sort social results" in {
      running(new ShoeboxApplication(modules: _*)) {
        val u1 = inject[Database].readWrite { implicit session =>

          val userRepo = inject[UserRepo]
          val userConnRepo = inject[UserConnectionRepo]
          val socialConnRepo = inject[SocialConnectionRepo]
          val suiRepo = inject[SocialUserInfoRepo]

          val u1 = userRepo.save(User(firstName = "Foo", lastName = "Bar"))
          val u2 = userRepo.save(User(firstName = "Douglas", lastName = "Adams"))
          userConnRepo.addConnections(u1.id.get, Set(u2.id.get))

          val c1 = SocialUser(IdentityId("id1", "facebook"), "Foo", "Bar", "Foo Bar", None, None, AuthenticationMethod.OAuth2, None, None)
          val su1a = suiRepo.save(SocialUserInfo(fullName = "Foo Bar", socialId = SocialId("foo1"), networkType = SocialNetworks.FACEBOOK, userId = u1.id, credentials = Some(c1)))
          val su1b = suiRepo.save(SocialUserInfo(fullName = "Foo Bar", socialId = SocialId("foo2"), networkType = SocialNetworks.LINKEDIN, userId = u1.id))

          val su2  = suiRepo.save(SocialUserInfo(fullName = "Douglas Adams", socialId = SocialId("doug"), networkType = SocialNetworks.FACEBOOK, userId = u2.id))

          val su3a = suiRepo.save(SocialUserInfo(fullName = "陳家洛", socialId = SocialId("chan1"), networkType = SocialNetworks.FACEBOOK, userId = None))
          val su3b = suiRepo.save(SocialUserInfo(fullName = "陳家洛 先生", socialId = SocialId("chan2"), networkType = SocialNetworks.LINKEDIN, userId = None))

          val su4  = suiRepo.save(SocialUserInfo(fullName = "郭靖", socialId = SocialId("kwok"), networkType = SocialNetworks.LINKEDIN, userId = None))

          socialConnRepo.save(SocialConnection(socialUser1 = su1a.id.get, socialUser2 = su3a.id.get))
          socialConnRepo.save(SocialConnection(socialUser1 = su1b.id.get, socialUser2 = su3b.id.get))
          u1
        }

        inject[FakeActionAuthenticator].setUser(u1, Set(ExperimentType.ADMIN))

        val path = com.keepit.controllers.website.routes.TypeaheadController.searchWithInviteStatus(Some("陳"), Some(10), false, true).toString
        val res = route(FakeRequest("GET", path)).get
        val s = contentAsString(res)
        val parsed = Json.parse(s).as[Seq[ConnectionWithInviteStatus]]
        println(s"content=$s parsed=$parsed")
        parsed.length === 2
        parsed(0).networkType === SocialNetworks.FACEBOOK.name
        parsed(0).label === "陳家洛"
        parsed(1).networkType === SocialNetworks.LINKEDIN.name
        parsed(1).label === "陳家洛 先生"
      }
    }

  }
}
