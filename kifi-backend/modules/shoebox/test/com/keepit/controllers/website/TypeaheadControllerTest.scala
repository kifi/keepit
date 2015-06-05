package com.keepit.controllers.website

import com.keepit.abook.model.RichContact
import com.keepit.abook.{ ABookServiceClient, FakeABookServiceClientImpl, FakeABookServiceClientModule }
import com.keepit.commanders._
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller._
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database

import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.EmailAddress
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.{ SocialId, SocialNetworks }
import com.keepit.test.ShoeboxTestInjector
import com.keepit.typeahead.TypeaheadHit
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsValue, Json }
import play.api.test.Helpers._
import play.api.test._
import securesocial.core._
import com.keepit.common.core._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

class TypeaheadControllerTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeHttpClientModule(),
    FakeUserActionsModule(),
    FakeShoeboxServiceModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeCortexServiceClientModule()
  )

  "TypeaheadController" should {

    "query & sort & limit results" in {
      withDb(modules: _*) { implicit injector =>
        val u1 = inject[Database].readWrite { implicit session =>

          val userRepo = inject[UserRepo]
          val userConnRepo = inject[UserConnectionRepo]
          val socialConnRepo = inject[SocialConnectionRepo]
          val suiRepo = inject[SocialUserInfoRepo]

          val u1 = UserFactory.user().withName("Foo", "Bar").withUsername("test").saved
          val u2 = UserFactory.user().withName("Douglas", "Adams").withUsername("test").saved
          userConnRepo.addConnections(u1.id.get, Set(u2.id.get))

          val c1 = SocialUser(IdentityId("id1", "facebook"), "Foo", "Bar", "Foo Bar", None, None, AuthenticationMethod.OAuth2, None, None)
          val su1a = suiRepo.save(SocialUserInfo(fullName = "Foo Bar", socialId = SocialId("foo1"), networkType = SocialNetworks.FACEBOOK, userId = u1.id, credentials = Some(c1)))
          val su1b = suiRepo.save(SocialUserInfo(fullName = "Foo Bar", socialId = SocialId("foo2"), networkType = SocialNetworks.LINKEDIN, userId = u1.id))

          val su2 = suiRepo.save(SocialUserInfo(fullName = "Douglas Adams", socialId = SocialId("doug"), networkType = SocialNetworks.FACEBOOK, userId = u2.id))

          val su3a = suiRepo.save(SocialUserInfo(fullName = "陳家洛", socialId = SocialId("chan1"), networkType = SocialNetworks.FACEBOOK, userId = None))
          val su3b = suiRepo.save(SocialUserInfo(fullName = "陳家洛 先生", socialId = SocialId("chan2"), networkType = SocialNetworks.LINKEDIN, userId = None))

          val su4 = suiRepo.save(SocialUserInfo(fullName = "郭靖", socialId = SocialId("kwok"), networkType = SocialNetworks.LINKEDIN, userId = None))

          val su5 = suiRepo.save(SocialUserInfo(fullName = "Andrew Ng", socialId = SocialId("andrew"), networkType = SocialNetworks.LINKEDIN, userId = None))
          val su6 = suiRepo.save(SocialUserInfo(fullName = "Ng Kar Ho", socialId = SocialId("raymond"), networkType = SocialNetworks.LINKEDIN, userId = None))
          val su7 = suiRepo.save(SocialUserInfo(fullName = "Julie Andrews", socialId = SocialId("julie.andrews"), networkType = SocialNetworks.LINKEDIN, userId = None))
          val su8 = suiRepo.save(SocialUserInfo(fullName = "Julie Ng", socialId = SocialId("julie.ng"), networkType = SocialNetworks.LINKEDIN, userId = None))

          socialConnRepo.save(SocialConnection(socialUser1 = su1a.id.get, socialUser2 = su3a.id.get))
          socialConnRepo.save(SocialConnection(socialUser1 = su1b.id.get, socialUser2 = su3b.id.get))
          socialConnRepo.save(SocialConnection(socialUser1 = su1b.id.get, socialUser2 = su5.id.get))
          socialConnRepo.save(SocialConnection(socialUser1 = su1b.id.get, socialUser2 = su6.id.get))
          socialConnRepo.save(SocialConnection(socialUser1 = su1b.id.get, socialUser2 = su7.id.get))
          socialConnRepo.save(SocialConnection(socialUser1 = su1b.id.get, socialUser2 = su8.id.get))

          u1
        }
        val abookClient = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
        abookClient.addTypeaheadHits(u1.id.get, Seq(TypeaheadHit[RichContact](0, "陳家洛", 0, RichContact(EmailAddress("chan@jing.com"), Some("陳家洛 電郵")))))
        inject[FakeUserActionsHelper].setUser(u1)

        @inline def search(query: String, limit: Int = 10): Seq[ConnectionWithInviteStatus] = {
          val path = com.keepit.controllers.website.routes.TypeaheadController.searchWithInviteStatus(Some(query), Some(limit), false, true).url
          val res = inject[TypeaheadController].searchWithInviteStatus(Some(query), Some(limit), false, true)(FakeRequest("GET", path))
          val s = contentAsString(res)
          Json.parse(s).as[Seq[ConnectionWithInviteStatus]] tap { res => log.info(s"[search($query,$limit)] res(len=${res.length}):$res") }
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

        // limit

        val res2a = search("陳家", 2)
        res2a.length === 2

        val res2b = search("陳家", 1)
        res2b.length === 1

        val res3 = search("Ng")
        res3.length === 3

        val res3a = search("Ng", 2)
        res3a.length === 2
      }
    }

    "query & dedup email contacts (userId)" in { // product requirement
      withDb(modules: _*) { implicit injector =>
        val (u1, u2) = inject[Database].readWrite { implicit session =>

          val userRepo = inject[UserRepo]
          val userConnRepo = inject[UserConnectionRepo]
          val socialConnRepo = inject[SocialConnectionRepo]
          val suiRepo = inject[SocialUserInfoRepo]

          val u1 = UserFactory.user().withName("Foo", "Bar").withUsername("test").saved
          val u2 = UserFactory.user().withName("Douglas", "Adams").withUsername("test").saved
          userConnRepo.addConnections(u1.id.get, Set(u2.id.get))

          val c1 = SocialUser(IdentityId("id1", "facebook"), "Foo", "Bar", "Foo Bar", None, None, AuthenticationMethod.OAuth2, None, None)
          val su1a = suiRepo.save(SocialUserInfo(fullName = "Foo Bar", socialId = SocialId("foo1"), networkType = SocialNetworks.FACEBOOK, userId = u1.id, credentials = Some(c1)))
          val su1b = suiRepo.save(SocialUserInfo(fullName = "Foo Bar", socialId = SocialId("foo2"), networkType = SocialNetworks.LINKEDIN, userId = u1.id))

          val su2 = suiRepo.save(SocialUserInfo(fullName = "Douglas Adams", socialId = SocialId("doug"), networkType = SocialNetworks.FACEBOOK, userId = u2.id))

          val su3a = suiRepo.save(SocialUserInfo(fullName = "陳家洛", socialId = SocialId("chan1"), networkType = SocialNetworks.FACEBOOK, userId = None))
          val su3b = suiRepo.save(SocialUserInfo(fullName = "陳家洛 先生", socialId = SocialId("chan2"), networkType = SocialNetworks.LINKEDIN, userId = None))

          val su4 = suiRepo.save(SocialUserInfo(fullName = "郭靖 先生", socialId = SocialId("kwok"), networkType = SocialNetworks.LINKEDIN, userId = None))
          val su5 = suiRepo.save(SocialUserInfo(fullName = "郭靖 tweets", socialId = SocialId("kwok"), networkType = SocialNetworks.TWITTER, userId = None)) // should not show up

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

        inject[FakeUserActionsHelper].setUser(u1)

        @inline def search(query: String, limit: Int = 10): Seq[ConnectionWithInviteStatus] = {
          val path = com.keepit.controllers.website.routes.TypeaheadController.searchWithInviteStatus(Some(query), Some(limit), false, true).url
          val res = inject[TypeaheadController].searchWithInviteStatus(Some(query), Some(limit), false, true)(FakeRequest("GET", path))
          val s = contentAsString(res)
          Json.parse(s).as[Seq[ConnectionWithInviteStatus]] tap { res => log.info(s"[search($query,$limit)] res(len=${res.length}):$res") }
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

        val res3a = search("郭靖", 1)
        res3a.length === 1 // LNKD
        res3a(0).label === "郭靖 先生"
      }
    }

    "query & dedup email contacts (email)" in {
      withDb(modules: _*) { implicit injector =>
        val (u1, u2) = inject[Database].readWrite { implicit session =>

          val userRepo = inject[UserRepo]
          val userConnRepo = inject[UserConnectionRepo]
          val socialConnRepo = inject[SocialConnectionRepo]
          val suiRepo = inject[SocialUserInfoRepo]

          val u1 = UserFactory.user().withName("Foo", "Bar").withUsername("test").saved
          val u2 = UserFactory.user().withName("Douglas", "Adams").withUsername("test").saved
          userConnRepo.addConnections(u1.id.get, Set(u2.id.get))

          val c1 = SocialUser(IdentityId("id1", "facebook"), "Foo", "Bar", "Foo Bar", None, None, AuthenticationMethod.OAuth2, None, None)
          val su1a = suiRepo.save(SocialUserInfo(fullName = "Foo Bar", socialId = SocialId("foo1"), networkType = SocialNetworks.FACEBOOK, userId = u1.id, credentials = Some(c1)))
          val su1b = suiRepo.save(SocialUserInfo(fullName = "Foo Bar", socialId = SocialId("foo2"), networkType = SocialNetworks.LINKEDIN, userId = u1.id))

          val su2 = suiRepo.save(SocialUserInfo(fullName = "Douglas Adams", socialId = SocialId("doug"), networkType = SocialNetworks.FACEBOOK, userId = u2.id))

          val su3a = suiRepo.save(SocialUserInfo(fullName = "陳家洛", socialId = SocialId("chan1"), networkType = SocialNetworks.FACEBOOK, userId = None))
          val su3b = suiRepo.save(SocialUserInfo(fullName = "陳家洛 先生", socialId = SocialId("chan2"), networkType = SocialNetworks.LINKEDIN, userId = None))

          val su4 = suiRepo.save(SocialUserInfo(fullName = "郭靖 先生", socialId = SocialId("kwok"), networkType = SocialNetworks.LINKEDIN, userId = None))
          val su5 = suiRepo.save(SocialUserInfo(fullName = "郭靖 tweets", socialId = SocialId("kwok"), networkType = SocialNetworks.TWITTER, userId = None)) // should not show up

          socialConnRepo.save(SocialConnection(socialUser1 = su1a.id.get, socialUser2 = su3a.id.get))
          socialConnRepo.save(SocialConnection(socialUser1 = su1b.id.get, socialUser2 = su3b.id.get))
          socialConnRepo.save(SocialConnection(socialUser1 = su1b.id.get, socialUser2 = su4.id.get))
          (u1, u2)
        }
        val contacts = Seq(
          TypeaheadHit[RichContact](0, "陳家洛", 0, RichContact(EmailAddress("chan@jing.com"), Some("陳家洛 電郵"))),
          TypeaheadHit[RichContact](0, "陳生", 0, RichContact(EmailAddress("chan@jing.com"), Some("陳生 電郵"))), // dup email
          TypeaheadHit[RichContact](0, "Doug Adams", 0, RichContact(email = EmailAddress("adams@42.com"), name = Some("Douglas Adams"), userId = u2.id)),
          TypeaheadHit[RichContact](0, "Mr. Adams", 0, RichContact(email = EmailAddress("adams@42.com"), name = Some("Mr. Adams"), userId = u2.id)), // dup email
          TypeaheadHit[RichContact](0, "郭靖", 0, RichContact(email = EmailAddress("kwok@jing.com"), name = Some("郭靖 電郵"))),
          TypeaheadHit[RichContact](0, "郭生", 0, RichContact(email = EmailAddress("kwok@jing.com"), name = Some("郭生 電郵"))) // dup email
        )
        val abookClient = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
        abookClient.addTypeaheadHits(u1.id.get, contacts)

        inject[FakeUserActionsHelper].setUser(u1)

        @inline def search(query: String, limit: Int = 10): Seq[ConnectionWithInviteStatus] = {
          val path = com.keepit.controllers.website.routes.TypeaheadController.searchWithInviteStatus(Some(query), Some(limit), false, true).url
          val res = inject[TypeaheadController].searchWithInviteStatus(Some(query), Some(limit), false, true)(FakeRequest("GET", path))
          val s = contentAsString(res)
          Json.parse(s).as[Seq[ConnectionWithInviteStatus]] tap { res => log.info(s"[search($query,$limit)] res(len=${res.length}):$res") }
        }

        val res1 = search("chan") // chan@jing.com (deduped)
        res1.length === 1
        res1.head.value === "email/chan@jing.com"

        val res2 = search("doug")
        res2.length === 1
        res2.head.value === s"fortytwo/${u2.externalId}" // email deduped due (userId set)

        val res3 = search("郭靖")
        res3.length === 2 // LNKD, EMAIL
        res3(0).label === "郭靖 先生"
        res3(1).label === "郭靖 電郵" // email lower priority (& deduped)

        val res3a = search("郭靖", 1)
        res3a.length === 1 // LNKD
        res3a(0).label === "郭靖 先生"

        val res4 = search("kwok") // kwok@jing.com (deduped)
        res4.length === 1
        res4.head.value === "email/kwok@jing.com"
      }
    }

    "query contacts" in {
      withDb(modules: _*) { implicit injector =>
        val userInteractionCommander = inject[UserInteractionCommander]
        val (u1, u2, u3, u4, u5) = db.readWrite { implicit session =>
          val u1 = UserFactory.user().withName("Spongebob", "Squarepants").withUsername("test").saved
          val u2 = UserFactory.user().withName("Patrick", "Star").withUsername("test").saved
          val u3 = UserFactory.user().withName("Squidward", "Tentacles").withUsername("test").saved
          val u4 = EmailAddress("squirrelsandy@texas.gov")
          val u5 = EmailAddress("mrkrabs@krusty.com")

          userConnRepo.addConnections(u1.id.get, Set(u2.id.get))
          userConnRepo.addConnections(u1.id.get, Set(u3.id.get))

          (u1, u2, u3, u4, u5)
        }

        val interactions = Seq(
          (UserRecipient(u2.id.get), UserInteraction.INVITE_LIBRARY),
          (UserRecipient(u3.id.get), UserInteraction.INVITE_LIBRARY),
          (EmailRecipient(u4), UserInteraction.INVITE_LIBRARY),
          (EmailRecipient(u5), UserInteraction.INVITE_LIBRARY))
        userInteractionCommander.addInteractions(u1.id.get, interactions)

        val abookClient = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
        abookClient.addTypeaheadHits(u1.id.get, Seq(TypeaheadHit[RichContact](0, "mrkrabs", 0, RichContact(u5, Some("Krabs")))))
        abookClient.addTypeaheadHits(u1.id.get, Seq(TypeaheadHit[RichContact](0, "sandysquirrel", 0, RichContact(u4, Some("SandySquirrel")))))

        inject[FakeUserActionsHelper].setUser(u1)

        @inline def search(query: String, limit: Int = 10): Seq[ContactSearchResult] = {
          val path = com.keepit.controllers.website.routes.TypeaheadController.searchForContacts(Some(query), Some(limit)).url
          val res = inject[TypeaheadController].searchForContacts(Some(query), Some(limit))(FakeRequest("GET", path))
          val js = Json.parse(contentAsString(res)).as[Seq[JsValue]].map { j =>
            (j \ "id").asOpt[ExternalId[User]] match {
              case Some(id) => j.as[UserContactResult]
              case None => j.as[EmailContactResult]
            }
          }
          js
        }

        def parseRes(contacts: Seq[ContactSearchResult]) = {
          contacts.collect {
            case u: UserContactResult => u.name
            case e: EmailContactResult => e.name.get
          }
        }

        val res0 = search("") // (no query) should get all contacts in Recent_Interactions
        res0.length === 4

        val res1 = search("s") // "one letter" -- abook skipped
        res1.length === 2
        parseRes(res1) === Seq("Squidward Tentacles", "Patrick Star")

        val res2 = search("sq")
        res2.length === 2
        parseRes(res2) === Seq("Squidward Tentacles", "SandySquirrel")

        val res3 = search("squid")
        res3.length === 1
        parseRes(res3) === Seq("Squidward Tentacles")

        val res4 = search("squir")
        res4.length === 1
        parseRes(res4) === Seq("SandySquirrel")
      }
    }

    "query & dedup contacts" in {
      withDb(modules: _*) { implicit injector =>
        val userInteractionCommander = inject[UserInteractionCommander]
        val (u1, u2, u3, u4, u5) = db.readWrite { implicit session =>
          val u1 = UserFactory.user().withName("Spongebob", "Squarepants").withUsername("test").saved
          val u2 = UserFactory.user().withName("Patrick", "Star").withUsername("test").saved
          val u3 = UserFactory.user().withName("Squidward", "Tentacles").withUsername("test").saved
          val u4 = EmailAddress("squirrelsandy@texas.gov")
          val u5 = EmailAddress("mrkrabs@krusty.com")

          userConnRepo.addConnections(u1.id.get, Set(u2.id.get))
          userConnRepo.addConnections(u1.id.get, Set(u3.id.get))

          (u1, u2, u3, u4, u5)
        }

        val interactions = Seq(
          (UserRecipient(u2.id.get), UserInteraction.INVITE_LIBRARY),
          (UserRecipient(u3.id.get), UserInteraction.INVITE_LIBRARY),
          (EmailRecipient(u4), UserInteraction.INVITE_LIBRARY),
          (EmailRecipient(u5), UserInteraction.INVITE_LIBRARY))
        userInteractionCommander.addInteractions(u1.id.get, interactions)

        val contacts = Seq(
          TypeaheadHit[RichContact](0, "陳家洛", 0, RichContact(EmailAddress("chan@jing.com"), Some("陳家洛 電郵"))),
          TypeaheadHit[RichContact](0, "陳生", 0, RichContact(EmailAddress("chan@jing.com"), Some("陳生 電郵"))), // dup email
          TypeaheadHit[RichContact](0, "郭靖", 0, RichContact(email = EmailAddress("kwok@jing.com"), name = Some("郭靖 電郵"))),
          TypeaheadHit[RichContact](0, "郭生", 0, RichContact(email = EmailAddress("kwok@jing.com"), name = Some("郭生 電郵"))) // dup email
        )
        val abookClient = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
        abookClient.addTypeaheadHits(u1.id.get, contacts)

        inject[FakeUserActionsHelper].setUser(u1)

        @inline def search(query: String, limit: Int = 10): Seq[ContactSearchResult] = {
          val path = com.keepit.controllers.website.routes.TypeaheadController.searchForContacts(Some(query), Some(limit)).url
          val res = inject[TypeaheadController].searchForContacts(Some(query), Some(limit))(FakeRequest("GET", path))
          val js = Json.parse(contentAsString(res)).as[Seq[JsValue]].map { j =>
            (j \ "id").asOpt[ExternalId[User]] match {
              case Some(id) => j.as[UserContactResult]
              case None => j.as[EmailContactResult]
            }
          }
          js
        }

        def parseRes(contacts: Seq[ContactSearchResult]) = {
          contacts.collect {
            case u: UserContactResult => u.name
            case e: EmailContactResult => e.email.address
          }
        }

        val res0 = search("") // (no query) should get all contacts in Recent_Interactions
        res0.length === 4

        val res1 = search("chan") // chan@jing.com (deduped)
        res1.length === 1
        parseRes(res1) === Seq("chan@jing.com")

        val res2 = search("kwok") // deduped
        res2.length === 1
        parseRes(res2) === Seq("kwok@jing.com")

        val res3 = search("squid")
        res3.length === 1
        parseRes(res3) === Seq("Squidward Tentacles")

      }
    }

  }
}
