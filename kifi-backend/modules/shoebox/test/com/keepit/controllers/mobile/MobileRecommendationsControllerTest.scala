package com.keepit.controllers.mobile

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.{ FakeShoeboxCommandersModule, FakeRecommendationsCommander, RecommendationsCommander }
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.controller.{ FakeUserActionsModule, FakeUserActionsHelper, UserActionsHelper }
import com.keepit.common.db.ExternalId
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.controllers.website.RecommendationsController
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.curator.model.{ FullRecoInfo, RecoAttributionInfo, RecoAttributionKind, RecoKind, RecoMetaData, UriRecoItemInfo }
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ NormalizedURI, URISummary, User, Username }
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.BasicUser
import com.keepit.test.ShoeboxTestInjector
import net.codingwell.scalaguice.ScalaModule
import org.joda.time.DateTime
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class MobileRecommendationsControllerTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  val modules = Seq(
    FakeShoeboxServiceModule(),
    FakeCuratorServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHeimdalServiceClientModule(),
    FakeMailModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeActorSystemModule(),
    FakeUserActionsModule(),
    FakeShoeboxCommandersModule()
  )

  "MobileRecommendationsController" should {

    "topRecos" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit s =>
          userRepo.save(User(firstName = "Andrew", lastName = "C", username = Username("test"), normalizedUsername = "test"))
        }

        inject[UserActionsHelper].asInstanceOf[FakeUserActionsHelper].setUser(user, Set())

        val route = com.keepit.controllers.mobile.routes.MobileRecommendationsController.topRecos(true, 0.75f).url
        route === "/m/1/recos/top?more=true&recency=0.75"

        val request = FakeRequest("GET", route)

        val controller = inject[RecommendationsController]
        val result: Future[Result] = controller.topRecos(true, 0.75f)(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val json = Json.parse(contentAsString(result))
        json === Json.parse("[]")

        val recoInfos = Seq(FullRecoInfo(
          kind = RecoKind.Keep,
          metaData = Some(RecoMetaData(attribution = Seq(RecoAttributionInfo(
            kind = RecoAttributionKind.Keep,
            name = Some("foo"),
            url = Some("http://foo.com"),
            when = Some(new DateTime(2013, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)))))),
          itemInfo = UriRecoItemInfo(id = ExternalId[NormalizedURI]("0cb299dd-5b9e-47b1-8377-3692051dd972"),
            title = Some("bar"),
            url = "http://bar.com",
            keepers = Seq(BasicUser(externalId = ExternalId[User]("aa25f5a8-8dea-4e56-82c1-a4dcf38f205c"),
              firstName = "Joe",
              lastName = "Smith",
              pictureName = "asdf",
              username = Username("joe"))),
            libraries = Seq.empty,
            others = 12,
            siteName = Some("fafa"),
            summary = URISummary(title = Some("Yo!"))),
          explain = Some("because :-)")))
        inject[RecommendationsCommander].asInstanceOf[FakeRecommendationsCommander].recoInfos = recoInfos

        val result2: Future[Result] = controller.topRecos(true, 0.75f)(request)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        val json2 = Json.parse(contentAsString(result2))
        val expected =
          """[{"kind":"keep",
             "metaData":[
               {"kind":"keep","name":"foo","url":"http://foo.com","when":1369972982001}
             ],
             "itemInfo":{"id":"0cb299dd-5b9e-47b1-8377-3692051dd972",
             "title":"bar",
             "url":"http://bar.com",
             "keepers":[{"id":"aa25f5a8-8dea-4e56-82c1-a4dcf38f205c","firstName":"Joe","lastName":"Smith","pictureName":"asdf","username":"joe"}],
             "libraries": [],
             "others":12,
             "siteName":"fafa",
             "summary":{"title":"Yo!"}},
             "explain":"because :-)"}]""".stripMargin
        json2 === Json.parse(expected)

      }
    }

    "topPublicRecos" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit s =>
          userRepo.save(User(firstName = "Andrew", lastName = "C", username = Username("test"), normalizedUsername = "test"))
        }

        inject[UserActionsHelper].asInstanceOf[FakeUserActionsHelper].setUser(user, Set())

        val route = com.keepit.controllers.mobile.routes.MobileRecommendationsController.topPublicRecos().url
        route === "/m/1/recos/public"

        val request = FakeRequest("GET", route)

        val controller = inject[RecommendationsController]
        val result: Future[Result] = controller.topPublicRecos()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val json = Json.parse(contentAsString(result))
        json === Json.parse("[]")

        val recoInfos = Seq(FullRecoInfo(
          kind = RecoKind.Keep,
          metaData = Some(RecoMetaData(attribution = Seq(RecoAttributionInfo(
            kind = RecoAttributionKind.Keep,
            name = Some("foo"),
            url = Some("http://foo.com"),
            when = Some(new DateTime(2013, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)))))),
          itemInfo = UriRecoItemInfo(id = ExternalId[NormalizedURI]("0cb299dd-5b9e-47b1-8377-3692051dd972"),
            title = Some("bar"),
            url = "http://bar.com",
            keepers = Seq(BasicUser(externalId = ExternalId[User]("aa25f5a8-8dea-4e56-82c1-a4dcf38f205c"),
              firstName = "Joe",
              lastName = "Smith",
              pictureName = "asdf",
              username = Username("joe"))),
            libraries = Seq.empty,
            others = 12,
            siteName = Some("fafa"),
            summary = URISummary(title = Some("Yo!"))),
          explain = Some("because :-)")))
        inject[RecommendationsCommander].asInstanceOf[FakeRecommendationsCommander].recoInfos = recoInfos

        val result2: Future[Result] = controller.topRecos(true, 0.75f)(request)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        val json2 = Json.parse(contentAsString(result2))
        val expected =
          """[{"kind":"keep",
             "metaData":[
               {"kind":"keep","name":"foo","url":"http://foo.com","when":1369972982001}
             ],
             "itemInfo":{"id":"0cb299dd-5b9e-47b1-8377-3692051dd972",
             "title":"bar",
             "url":"http://bar.com",
             "keepers":[{"id":"aa25f5a8-8dea-4e56-82c1-a4dcf38f205c","firstName":"Joe","lastName":"Smith","pictureName":"asdf","username":"joe"}],
             "libraries": [],
             "others":12,
             "siteName":"fafa",
             "summary":{"title":"Yo!"}},
             "explain":"because :-)"}]""".stripMargin
        json2 === Json.parse(expected)

      }
    }
  }
}
