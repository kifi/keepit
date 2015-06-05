package com.keepit.search.controllers.ext

import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.search.engine.uri.{ UriShardHit, UriShardResult, UriSearchResult }
import com.keepit.search.test.SearchTestInjector
import org.specs2.mutable._
import com.keepit.model._
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller.{ FakeSecureSocialClientIdModule, FakeUserActionsHelper, FakeUserActionsModule }
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.libs.json._
import com.keepit.search._
import com.keepit.social.BasicUser
import com.keepit.search.result._
import com.keepit.search.result.DecoratedResult
import com.keepit.common.util.PlayAppConfigurationModule
import com.keepit.search.controllers.{ FixedResultUriSearchCommander, FixedResultIndexModule }

class ExtSearchControllerTest extends Specification with SearchTestInjector {

  def modules = {
    Seq(
      FakeActorSystemModule(),
      FakeSecureSocialClientIdModule(),
      FakeUserActionsModule(),
      FixedResultIndexModule(),
      PlayAppConfigurationModule(),
      FakeCryptoModule()
    )
  }

  "ExtSearchController" should {
    "search keeps" in {
      withInjector(modules: _*) { implicit injector =>
        val path = routes.ExtSearchController.search("test", None, 7, None, None, None, None, None, None, None, None).url
        path === "/search?q=test&maxHits=7"

        inject[UriSearchCommander].asInstanceOf[FixedResultUriSearchCommander].setDecoratedResults(ExtSearchControllerTest.decoratedTestResults)
        val user = UserFactory.user().withId(1).withName("prénom", "nom").withUsername("test").get
        inject[FakeUserActionsHelper].setUser(user)
        val request = FakeRequest("GET", path)
        val result = inject[ExtSearchController].search("test", None, 7, None, None, None, None, None, None, None, None)(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
          {
            "uuid":"21eb7aa7-97ba-466f-a357-c3511e4c8b29",
            "query":"test",
            "hits":
              [
                {
                  "count":2,
                  "bookmark":
                    {
                      "title":"this is a test",
                      "url":"http://kifi.com",
                      "id":"604754fb-182d-4c39-a314-2d1994b24159",
                      "matches":
                        {
                          "title":[[9,4]]
                        },
                      "tags":
                        [
                          "c17da7ce-64bb-4c91-8832-1f1a6a88b7be",
                          "19ccb3db-4e18-4ade-91bd-1a98ef33aa63"
                        ]
                      },
                    "users":
                      [
                        {
                          "id":"4e5f7b8c-951b-4497-8661-a1001885b2ec",
                          "firstName":"Vorname",
                          "lastName":"Nachname",
                          "pictureName":"1.jpg",
                          "username":"vorname"
                        }
                      ],
                    "score":0.9990000128746033,
                    "isMyBookmark":true,
                    "isPrivate":false
                }
              ],
            "myTotal":1,
            "friendsTotal":12,
            "othersTotal":123,
            "mayHaveMore":false,
            "show":true,
            "experimentId":10,
            "context":"AgFJAN8CZHg",
            "experts":[]
          }
        """)
        // println(Json.parse(contentAsString(result)).toString) // can be removed?
        Json.parse(contentAsString(result)) === expected
      }
    }

    "search keeps with library support and JSON response" in {
      withInjector(modules: _*) { implicit injector =>
        val path = routes.ExtSearchController.search2("test", 2, None, None, None, None, None).url
        path === "/ext/search?q=test&n=2"

        inject[UriSearchCommander].asInstanceOf[FixedResultUriSearchCommander].setPlainResults(ExtSearchControllerTest.plainTestResults)
        val user = UserFactory.user().withId(1).withName("prénom", "nom").withUsername("test").get
        inject[FakeUserActionsHelper].setUser(user)
        val request = FakeRequest("GET", path)
        val result = inject[ExtSearchController].search2("test", 2, None, None, None, None, None)(request)
        status(result) === OK
        contentType(result) === Some("application/json")
        contentAsString(result) === "1\r\n" + // chunk byte count
          "[\r\n" +
          "122\r\n" + // chunk byte count
          """
          {
            "uuid":"98765432-1234-5678-9abc-fedcba987654",
            "query":"test",
            "hits":[{"title":"Example Site","url":"http://example.com","keepId":"604754fb-182d-4c39-a314-2d1994b24159"}],
            "myTotal":12,
            "friendsTotal":23,
            "mayHaveMore":true,
            "show":true,
            "cutPoint":1,
            "experimentId":null,
            "context":"AgFJAN8CZHg"
          }""".replaceAll("\n *", "") + "\r\n" +
          "1\r\n" + // chunk byte count
          ",\r\n" +
          "27\r\n" + // chunk byte count
          """{"hits":[{}],"users":[],"libraries":[]}""" + "\r\n" +
          "1\r\n" + // chunk byte count
          "]\r\n" +
          "0\r\n" + // chunk byte count
          "\r\n"
      }
    }

    "search keeps with library support and a plain text response" in {
      withInjector(modules: _*) { implicit injector =>
        val path = routes.ExtSearchController.search2("test", 2, None, None, None, None, None).url
        path === "/ext/search?q=test&n=2"

        inject[UriSearchCommander].asInstanceOf[FixedResultUriSearchCommander].setPlainResults(ExtSearchControllerTest.plainTestResults)
        val user = UserFactory.user().withId(1).withName("prénom", "nom").withUsername("test").get
        inject[FakeUserActionsHelper].setUser(user)
        val request = FakeRequest("GET", path).withHeaders("Accept" -> "text/plain")
        val result = inject[ExtSearchController].search2("test", 2, None, None, None, None, None)(request)
        status(result) === OK
        contentType(result) === Some("text/plain")
        contentAsString(result) === "122\r\n" + // chunk byte count
          """
          {
            "uuid":"98765432-1234-5678-9abc-fedcba987654",
            "query":"test",
            "hits":[{"title":"Example Site","url":"http://example.com","keepId":"604754fb-182d-4c39-a314-2d1994b24159"}],
            "myTotal":12,
            "friendsTotal":23,
            "mayHaveMore":true,
            "show":true,
            "cutPoint":1,
            "experimentId":null,
            "context":"AgFJAN8CZHg"
          }""".replaceAll("\n *", "") + "\r\n" +
          "27\r\n" + // chunk byte count
          """{"hits":[{}],"users":[],"libraries":[]}""" + "\r\n" +
          "0\r\n" + // chunk byte count
          "\r\n"
      }
    }
  }
}

object ExtSearchControllerTest {
  val decoratedTestResults: Map[String, DecoratedResult] = Map(
    "test" -> DecoratedResult(
      ExternalId[ArticleSearchResult]("21eb7aa7-97ba-466f-a357-c3511e4c8b29"), // uuid
      Seq[DetailedSearchHit]( // hits
        DetailedSearchHit(
          1000, // uriId
          2, // bookmarkCount
          BasicSearchHit(
            Some("this is a test"), // title
            "http://kifi.com",
            Some(Seq( // collections
              ExternalId[Collection]("c17da7ce-64bb-4c91-8832-1f1a6a88b7be"),
              ExternalId[Collection]("19ccb3db-4e18-4ade-91bd-1a98ef33aa63")
            )),
            Some(ExternalId[Keep]("604754fb-182d-4c39-a314-2d1994b24159")), // bookmarkId
            Some(Seq((9, 13))), // title matches
            None // url matches
          ),
          true, // isMyBookmark
          true, // isFriendsBookmark
          false, // isPrivate
          Seq(Id[User](999)), // users
          0.999f, // score
          10.0f, // textScore
          new Scoring( // scoring
            1.3f, // textScore
            1.0f, // normalizedTextScore,
            1.0f, // bookmarkScore
            0.5f, // recencyScore
            false // usefulPage
          )
        ).set("basicUsers", JsArray(Seq(Json.toJson(BasicUser(ExternalId[User]("4e5f7b8c-951b-4497-8661-a1001885b2ec"), "Vorname", "Nachname", "1.jpg", Username("vorname"))))))
      ),
      1, // myTotal
      12, // friendsTotal
      123, // othersTotal
      "test", // query
      Id[User](99), // userId
      Set(100, 220), // idFilter
      false, // mayHaveMoreHits
      true, //show
      Some(Id[SearchConfigExperiment](10)) //searchExperimentId
    )
  )

  val plainTestResults = Map(
    "test" -> new UriSearchResult(
      uuid = ExternalId[ArticleSearchResult]("98765432-1234-5678-9abc-fedcba987654"),
      query = "test",
      searchFilter = SearchFilter.default(),
      firstLang = Lang("en"),
      result = UriShardResult(
        hits = Seq(
          UriShardHit(
            id = 234,
            score = .999f,
            visibility = 0,
            libraryId = 678,
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
