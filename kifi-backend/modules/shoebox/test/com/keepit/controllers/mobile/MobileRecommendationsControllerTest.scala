package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.{ FakeRecommendationsCommander, FakeShoeboxCommandersModule, RecommendationsCommander }
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.controller.{ FakeUserActionsHelper, FakeUserActionsModule, UserActionsHelper }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{ Id, ExternalId }

import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.controllers.website.RecommendationsController
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.curator.model._
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.BasicUser
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{ JsArray, Json }
import play.api.mvc.{ AnyContent, Request, Call, Result }
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
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeActorSystemModule(),
    FakeUserActionsModule(),
    FakeShoeboxCommandersModule()
  )

  "MobileRecommendationsController" should {

    // Most of this block is setup code that is shared between 2 different tests
    "topRecos" should {

      def topRecosSetup()(implicit injector: Injector) = {
        val user1 = db.readWrite { implicit s => user().saved }
        inject[UserActionsHelper].asInstanceOf[FakeUserActionsHelper].setUser(user1, Set())
      }

      val basicUser1 = BasicUser(
        externalId = ExternalId[User]("aa25f5a8-8dea-4e56-82c1-a4dcf38f205c"),
        firstName = "Joe",
        lastName = "Smith",
        pictureName = "asdf",
        username = Username("joe"))

      val recoInfos = Seq(FullUriRecoInfo(
        kind = RecoKind.Keep,
        metaData = Some(RecoMetaData(attribution = Seq(RecoAttributionInfo(
          kind = RecoAttributionKind.Keep,
          name = Some("foo"),
          url = Some("http://foo.com"),
          when = Some(new DateTime(2013, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)))))),
        itemInfo = UriRecoItemInfo(id = ExternalId[NormalizedURI]("0cb299dd-5b9e-47b1-8377-3692051dd972"),
          title = Some("bar"),
          url = "http://bar.com",
          keepers = Seq(basicUser1),
          libraries = Seq.empty,
          others = 12,
          siteName = Some("fafa"),
          summary = URISummary(title = Some("Yo!"))),
        explain = Some("because :-)")))

      val expectedRecoInfosJson =
        """{"kind":"keep",
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
             "explain":"because :-)"}""".stripMargin

      val now = new DateTime(2013, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
      val libRecoInfos = Seq.tabulate(1) { i: Int =>
        Id[Library](i) -> FullLibRecoInfo(
          metaData = None,
          itemInfo = FullLibraryInfo(
            id = PublicId[Library]("123"),
            name = "Scala",
            visibility = LibraryVisibility.PUBLISHED,
            description = Some("This is a library about scala..."),
            slug = LibrarySlug("scala"),
            kind = LibraryKind.USER_CREATED,
            color = Some(LibraryColor.BLUE),
            image = None,
            owner = basicUser1,
            keeps = Seq(),
            followers = Seq(),
            collaborators = Seq(),
            lastKept = None,
            url = "joe/scala",
            numKeeps = 10,
            numCollaborators = 0,
            numFollowers = 10,
            whoCanInvite = "collaborator",
            modifiedAt = now
          )
        )
      }

      val expectedLibRecoInfosJson =
        s"""
          |{"kind":"library",
          | "itemInfo":{"id":"123","name":"Scala","visibility":"published",
          |   "description":"This is a library about scala...","slug":"scala","url":"joe/scala","color":"${LibraryColor.BLUE.hex}","kind":"user_created",
          |   "owner":{"id":"aa25f5a8-8dea-4e56-82c1-a4dcf38f205c","firstName":"Joe","lastName":"Smith","pictureName":"asdf","username":"joe"},
          |   "followers":[],"collaborators":[],"keeps":[],"numKeeps":10,"numCollaborators":0,"numFollowers":10,"whoCanInvite": "collaborator","modifiedAt":${now.getMillis}}}
        """.stripMargin

      def runCommonTopRecosTests(call: Call, requestFn: Request[AnyContent] => Future[Result])(implicit injector: Injector): Future[Result] = {
        val request = FakeRequest(call).withHeaders(USER_AGENT -> "iKeefee/0.0.0 (Device-Type: NA, OS: iOS NA)")

        val result: Future[Result] = requestFn(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val json = Json.parse(contentAsString(result))
        json === Json.parse("[]")

        val recoCommander = inject[RecommendationsCommander].asInstanceOf[FakeRecommendationsCommander]
        recoCommander.uriRecoInfos = recoInfos
        recoCommander.libRecoInfos = libRecoInfos

        val result2: Future[Result] = requestFn(request)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        result2
      }

      "version 2 includes keeps and libraries" in {
        withDb(modules: _*) { implicit injector =>
          topRecosSetup()

          val call = routes.MobileRecommendationsController.topRecosV2(0.75f, true)
          call.url === "/m/2/recos/top?recency=0.75&more=true"
          call.method === "GET"

          val result = runCommonTopRecosTests(call, request => inject[MobileRecommendationsController].topRecosV2(0.75f, true)(request))
          val json = Json.parse(contentAsString(result)).asInstanceOf[JsArray]
          json.value.size == 2

          val kind = (json(0) \ "kind").as[String]
          val (keepIdx, libIdx) = if (kind == "library") (1, 0) else (0, 1)

          json(libIdx) === Json.parse(expectedLibRecoInfosJson)
          json(keepIdx) === Json.parse(expectedRecoInfosJson)
        }
      }

      "version 3 passes and receives context info" in {
        withDb(modules: _*) { implicit injector =>
          topRecosSetup()

          val recoCommander = inject[RecommendationsCommander].asInstanceOf[FakeRecommendationsCommander]
          recoCommander.uriRecoInfos = recoInfos
          recoCommander.libRecoInfos = libRecoInfos

          val call = routes.MobileRecommendationsController.topRecosV3(0.75f, None, None)
          call.url === "/m/3/recos/top?recency=0.75"
          call.method === "GET"

          val request = FakeRequest(call).withHeaders(USER_AGENT -> "iKeefee/0.0.0 (Device-Type: NA, OS: iOS NA)")
          val result = inject[MobileRecommendationsController].topRecosV3(0.75f, None, None, None, None)(request)

          status(result) must equalTo(OK)
          contentType(result) must beSome("application/json")

          val js = Json.parse(contentAsString(result)) // expected: {"recos": [....], "uctx": "...", "lctx": "..."}
          val recos = (js \ "recos")

          val kind = (recos(0) \ "kind").as[String]
          val (keepIdx, libIdx) = if (kind == "library") (1, 0) else (0, 1)

          recos(libIdx) === Json.parse(expectedLibRecoInfosJson)
          recos(keepIdx) === Json.parse(expectedRecoInfosJson)

          (js \ "uctx").as[String] === "fake_uri_context"
          (js \ "lctx").as[String] === "fake_lib_context"

        }
      }
    }

    "topPublicRecos" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit s =>
          userRepo.save(User(firstName = "Andrew", lastName = "C", username = Username("test"), normalizedUsername = "test"))
        }

        inject[UserActionsHelper].asInstanceOf[FakeUserActionsHelper].setUser(user, Set())

        val route = routes.MobileRecommendationsController.topPublicRecos().url
        route === "/m/1/recos/public"

        val request = FakeRequest("GET", route).withHeaders(USER_AGENT -> "iKeefee/0.0.0 (Device-Type: NA, OS: iOS NA)")

        val controller = inject[MobileRecommendationsController]
        val result: Future[Result] = controller.topPublicRecos()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val json = Json.parse(contentAsString(result))
        json === Json.parse("[]")

        val recoInfos = Seq(FullUriRecoInfo(
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
        inject[RecommendationsCommander].asInstanceOf[FakeRecommendationsCommander].uriRecoInfos = recoInfos

        val result2: Future[Result] = controller.topRecosV2(0.75f, true)(request)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        val json2 = Json.parse(contentAsString(result2))
        val expectedRecoInfosJson =
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
        json2 === Json.parse(expectedRecoInfosJson)

      }
    }
  }
}
