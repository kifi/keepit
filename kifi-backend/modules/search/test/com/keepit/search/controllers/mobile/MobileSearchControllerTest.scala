package com.keepit.search.controllers.mobile

import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller.{ FakeUserActionsHelper, FakeUserActionsModule }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.model._
import com.keepit.search._
import com.keepit.search.result.{ DecoratedResult, _ }
import com.keepit.social.BasicUser
import com.keepit.search.test.SearchTestInjector
import com.keepit.common.util.PlayAppConfigurationModule
import org.specs2.mutable._
import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.search.controllers.{ FixedResultUriSearchCommander, FixedResultIndexModule }

class MobileSearchControllerTest extends SpecificationLike with SearchTestInjector {

  def modules = Seq(
    FakeActorSystemModule(),
    FakeUserActionsModule(),
    FixedResultIndexModule(),
    FakeHttpClientModule(),
    PlayAppConfigurationModule(),
    FakeCryptoModule()
  )

  "MobileSearchController" should {
    "search keeps (V1)" in {
      withInjector(modules: _*) { implicit injector =>
        val mobileSearchController = inject[MobileSearchController]
        val path = com.keepit.search.controllers.mobile.routes.MobileSearchController.searchV1("test", None, 7, None, None).toString
        path === "/m/1/search?q=test&maxHits=7"

        inject[UriSearchCommander].asInstanceOf[FixedResultUriSearchCommander].setDecoratedResults(MobileSearchControllerTest.decoratedTestResults)
        val user = UserFactory.user().withId(1).withName("prénom", "nom").withUsername("test").get
        inject[FakeUserActionsHelper].setUser(user)
        val request = FakeRequest("GET", path)
        val result = mobileSearchController.searchV1("test", None, 7, None, None)(request)
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
                      "url":"http://kifi.com/%5B%E3%83%86%E3%82%B9%E3%83%88%5D",
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
  }
}

object MobileSearchControllerTest {
  val decoratedTestResults: Map[String, DecoratedResult] = Map(
    "test" -> DecoratedResult(
      ExternalId[ArticleSearchResult]("21eb7aa7-97ba-466f-a357-c3511e4c8b29"), // uuid
      Seq[DetailedSearchHit]( // hits
        DetailedSearchHit(
          1000, // uriId
          2, // bookmarkCount
          BasicSearchHit(
            Some("this is a test"), // title
            "http://kifi.com/[テスト]", // url, '[' and ']' should be percent-encoded and the result json
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
}
