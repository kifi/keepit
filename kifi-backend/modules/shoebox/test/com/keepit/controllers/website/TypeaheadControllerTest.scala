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
import com.keepit.common.core._

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

        @inline def search(query: String, limit: Int = 10): Seq[ConnectionWithInviteStatus] = {
          val path = com.keepit.controllers.website.routes.TypeaheadController.searchWithInviteStatus(Some(query), Some(limit), false, true).url
          val res = inject[TypeaheadController].searchWithInviteStatus(Some(query), Some(limit), false, true)(FakeRequest("GET", path))
          val s = contentAsString(res)
          Json.parse(s).as[Seq[ConnectionWithInviteStatus]] tap { res => println(s"[search($query,$limit)] res(len=${res.length}):$res") }
        }

        val res1 = search("陳")
        res1.length === 2 // FB, LNKD; one "letter" -- abook skipped
        res1(0).networkType === SocialNetworks.FACEBOOK.name
        res1(0).label === "陳家洛"
        res1(1).networkType === SocialNetworks.LINKEDIN.name
        res1(1).label === "陳家洛 先生"

        val res2 = search("陳家")
        res2.length === 3 // FB, LNKD, EMAIL
        res2(0).networkType === SocialNetworks.FACEBOOK.name
        res2(0).label === "陳家洛"
        res2(1).networkType === SocialNetworks.LINKEDIN.name
        res2(1).label === "陳家洛 先生"
        res2(2).networkType === SocialNetworks.EMAIL.name
        res2(2).value === "email/chan@jing.com"
        res2(2).label === "陳家洛 電郵"
      }
    }

    "query & dedup email contacts" in { // product requirement
      withDb(modules: _*) { implicit injector =>
        val (u1, u2) = inject[Database].readWrite { implicit session =>

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

          val su4 = suiRepo.save(SocialUserInfo(fullName = "郭靖 先生", socialId = SocialId("kwok"), networkType = SocialNetworks.LINKEDIN, userId = None))

          socialConnRepo.save(SocialConnection(socialUser1 = su1a.id.get, socialUser2 = su3a.id.get))
          socialConnRepo.save(SocialConnection(socialUser1 = su1b.id.get, socialUser2 = su3b.id.get))
          socialConnRepo.save(SocialConnection(socialUser1 = su1b.id.get, socialUser2 = su4.id.get))
          (u1, u2)
        }
        val contacts = Seq(
          TypeaheadHit[RichContact](0, "陳家洛", 0, RichContact(EmailAddress("chan@jing.com"), Some("陳家洛 電郵"))),
          TypeaheadHit[RichContact](0, "Doug Adams", 0, RichContact(email = EmailAddress("adams@42.com"), name = Some("Douglas Adams"), userId = u2.id)),
          TypeaheadHit[RichContact](0, "郭靖", 0, RichContact(email = EmailAddress("kwok@jing.com"), name = Some("郭靖 電郵")))
        )
        val abookClient = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
        abookClient.addTypeaheadHits(u1.id.get, contacts)

        inject[FakeActionAuthenticator].setUser(u1)

        @inline def search(query: String, limit: Int = 10): Seq[ConnectionWithInviteStatus] = {
          val path = com.keepit.controllers.website.routes.TypeaheadController.searchWithInviteStatus(Some(query), Some(limit), false, true).url
          val res = inject[TypeaheadController].searchWithInviteStatus(Some(query), Some(limit), false, true)(FakeRequest("GET", path))
          val s = contentAsString(res)
          Json.parse(s).as[Seq[ConnectionWithInviteStatus]] tap { res => println(s"[search($query,$limit)] res(len=${res.length}):$res") }
        }

        val res1 = search("chan") // chan@jing.com
        res1.length === 1
        res1.head.label === "陳家洛 電郵"
        res1.head.value === "email/chan@jing.com"

        val res2 = search("doug")
        res2.length === 1 // email de-duped
        res2.head.label === "Douglas Adams"

        val res3 = search("郭靖")
        res3.length === 2 // LNKD, EMAIL
        res3(0).label === "郭靖 先生"
        res3(1).label === "郭靖 電郵"
      }
    }

  }
}
