package com.keepit.controllers.website

import com.keepit.abook.model.RichContact
import com.keepit.abook.{ ABookServiceClient, FakeABookServiceClientImpl, FakeABookServiceClientModule }
import com.keepit.commanders.ConnectionWithInviteStatus
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller._
import com.keepit.common.db.slick.Database
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.EmailAddress
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.{ SocialId, SocialNetworks }
import com.keepit.test.ShoeboxTestInjector
import com.keepit.typeahead.TypeaheadHit
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test._
import securesocial.core._

class TypeaheadControllerTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeExternalServiceModule(),
    FakeABookServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeCortexServiceClientModule()
  )

  "TypeaheadController" should {

    "query & sort results" in {
      withDb(modules: _*) { implicit injector =>
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

          val su2 = suiRepo.save(SocialUserInfo(fullName = "Douglas Adams", socialId = SocialId("doug"), networkType = SocialNetworks.FACEBOOK, userId = u2.id))

          val su3a = suiRepo.save(SocialUserInfo(fullName = "陳家洛", socialId = SocialId("chan1"), networkType = SocialNetworks.FACEBOOK, userId = None))
          val su3b = suiRepo.save(SocialUserInfo(fullName = "陳家洛 先生", socialId = SocialId("chan2"), networkType = SocialNetworks.LINKEDIN, userId = None))

          val su4 = suiRepo.save(SocialUserInfo(fullName = "郭靖", socialId = SocialId("kwok"), networkType = SocialNetworks.LINKEDIN, userId = None))

          socialConnRepo.save(SocialConnection(socialUser1 = su1a.id.get, socialUser2 = su3a.id.get))
          socialConnRepo.save(SocialConnection(socialUser1 = su1b.id.get, socialUser2 = su3b.id.get))
          u1
        }
        val abookClient = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
        abookClient.addTypeaheadHits(u1.id.get, Seq(TypeaheadHit[RichContact](0, "陳家洛", 0, RichContact(EmailAddress("chan@jing.com"), Some("陳家洛 電郵")))))
        inject[FakeActionAuthenticator].setUser(u1)

        val path = com.keepit.controllers.website.routes.TypeaheadController.searchWithInviteStatus(Some("陳家"), Some(10), false, true).url
        val res = inject[TypeaheadController].searchWithInviteStatus(Some("陳家"), Some(10), false, true)(FakeRequest("GET", path))
        val s = contentAsString(res)
        val parsed = Json.parse(s).as[Seq[ConnectionWithInviteStatus]]
        parsed.length === 3
        parsed(0).networkType === SocialNetworks.FACEBOOK.name
        parsed(0).label === "陳家洛"
        parsed(1).networkType === SocialNetworks.LINKEDIN.name
        parsed(1).label === "陳家洛 先生"
        parsed(2).networkType === SocialNetworks.EMAIL.name
        parsed(2).label === "陳家洛 電郵"
      }
    }

  }
}
