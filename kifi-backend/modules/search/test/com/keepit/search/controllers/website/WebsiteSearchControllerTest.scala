package com.keepit.search.controllers.website

import com.keepit.search.engine.uri.{ UriSearchResult, UriShardHit, UriShardResult }
import org.specs2.mutable.SpecificationLike
import com.keepit.search.test.SearchTestInjector
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.search._
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.controller.{ FakeUserActionsHelper, FakeUserActionsModule }
import com.keepit.common.util.PlayAppConfigurationModule
import com.keepit.search.controllers.{ FixedResultIndexModule, FixedResultUriSearchCommander }
import com.keepit.common.crypto.{ FakeCryptoModule, PublicIdConfiguration }
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.model._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.libs.json.Json
import com.keepit.shoebox.FakeShoeboxServiceModule

class WebsiteSearchControllerTest extends SpecificationLike with SearchTestInjector {

  def modules = Seq(
    FakeActorSystemModule(),
    FakeUserActionsModule(),
    FixedResultIndexModule(),
    FakeHttpClientModule(),
    PlayAppConfigurationModule(),
    FakeCryptoModule(),
    FakeShoeboxServiceModule()
  )

  "WebsiteSearchController" should {

    "search keeps" in {
      withInjector(modules: _*) { implicit injector =>
        val path = routes.WebsiteSearchController.search(q = "test", maxUris = 2).url
        path === "/site/search?q=test&maxUris=2"

        inject[UriSearchCommander].asInstanceOf[FixedResultUriSearchCommander].setPlainResults(ExtSearchControllerTest.plainTestResults)
        val user = UserFactory.user().withId(1).withName("prénom", "nom").withUsername("test").get
        inject[FakeUserActionsHelper].setUser(user)
        val request = FakeRequest("GET", path)
        val result = inject[WebsiteSearchController].search("test", None, None, None, None, 2, None, None, 0, None, 0, None, false, false, None, None, None, None)(request)
        status(result) === OK
        contentType(result) === Some("application/json")
        implicit val publicIdConfig = inject[PublicIdConfiguration]

        val expected = Json.parse(s"""{
          "query": "test",
          "uris": {
            "uuid":"98765432-1234-5678-9abc-fedcba987654",
            "context":"AgFJAN8CZHg",
            "mayHaveMore":true,
            "myTotal":12,
            "friendsTotal":23,
            "othersTotal":210,
            "hits":[{
              "title":"Example Site",
              "description":null,
              "wordCount":null,
              "url":"http://example.com",
              "uriId":"${NormalizedURI.publicId(Id(ExtSearchControllerTest.plainTestResults("test").hits.head.id)).id}",
              "siteName":"example.com",
              "image":null,
              "score":-1.0,
              "summary":{},
              "author":null,
              "user":null,
              "library":null,
              "createdAt":null,
              "note":null,
              "source":null,
              "sources":[],
              "keeps":[],
              "keepers":[],
              "keepersOmitted":0,
              "keepersTotal":0,
              "libraries":[],
              "librariesOmitted":0,
              "librariesTotal": 0,
              "tags":[],
              "tagsOmitted":0
            }],
            "libraries":[],
            "keepers":[]
          },
        "libraries": null,
        "users": null
        }""")
        Json.parse(contentAsString(result)) === expected
      }
    }
  }
}

object ExtSearchControllerTest {

  val plainTestResults = Map(
    "test" -> new UriSearchResult(
      uuid = ExternalId[ArticleSearchResult]("98765432-1234-5678-9abc-fedcba987654"),
      query = "test",
      searchFilter = SearchFilter.default,
      firstLang = Lang("en"),
      result = UriShardResult(
        hits = Seq(
          UriShardHit(
            id = 234,
            score = .999f,
            visibility = 0,
            keepId = 456,
            title = Some("Example Site"),
            url = "http://example.com",
            externalId = ExternalId[Keep]("604754fb-182d-4c39-a314-2d1994b24159"))),
        myTotal = 12,
        friendsTotal = 23,
        othersTotal = 210,
        show = true,
        cutPoint = 1),
      idFilter = Set(100, 220),
      searchExperimentId = None
    )
  )
}
