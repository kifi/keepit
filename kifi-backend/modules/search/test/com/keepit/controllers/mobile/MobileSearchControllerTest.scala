package com.keepit.controllers.mobile

import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.common.controller.{ FakeActionAuthenticator, FakeActionAuthenticatorModule }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.inject._
import com.keepit.model._
import com.keepit.search._
import com.keepit.search.index.{ IndexDirectory, IndexModule, IndexStore, VolatileIndexDirectory }
import com.keepit.search.result.{ DecoratedResult, _ }
import com.keepit.search.sharding.Shard
import com.keepit.social.BasicUser
import com.keepit.test.SearchTestInjector
import org.apache.lucene.search.{ Explanation, Query }
import com.keepit.common.util.Configuration
import com.keepit.common.util.PlayAppConfigurationModule
import org.specs2.mutable._
import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.Helpers._

class MobileSearchControllerTest extends SpecificationLike with SearchTestInjector {

  def modules = Seq(
    StandaloneTestActorSystemModule(),
    FakeActionAuthenticatorModule(),
    FixedResultIndexModule(),
    FakeHttpClientModule(),
    PlayAppConfigurationModule()
  )

  "MobileSearchController" should {
    "search keeps (V1)" in {
      withInjector(modules: _*) { implicit injector =>
        val mobileSearchController = inject[MobileSearchController]
        val path = com.keepit.controllers.mobile.routes.MobileSearchController.searchV1("test", None, 7, None, None, None, None, None, None, None).toString
        path === "/m/1/search?q=test&maxHits=7"

        val user = User(Some(Id[User](1)), firstName = "prénom", lastName = "nom")
        inject[FakeActionAuthenticator].setUser(user)
        val request = FakeRequest("GET", path)
        val result = mobileSearchController.searchV1("test", None, 7, None, None, None, None, None, None, None)(request)
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
            "context":"AgFJAN8CZHg=",
            "experts":[]
          }
        """)
        println(Json.parse(contentAsString(result)).toString)
        Json.parse(contentAsString(result)) === expected
      }
    }
  }
}

case class FixedResultIndexModule() extends IndexModule {
  var volatileDirMap = Map.empty[(String, Shard[_]), IndexDirectory] // just in case we need to reference a volatileDir. e.g. in spellIndexer

  protected def getIndexDirectory(configName: String, shard: Shard[_], indexStore: IndexStore, conf: Configuration): IndexDirectory = {
    volatileDirMap.getOrElse((configName, shard), {
      val newdir = new VolatileIndexDirectory()
      volatileDirMap += (configName, shard) -> newdir
      newdir
    })
  }
  override def configure() {
    super.configure()
    bind[SearchCommander].to[FixedResultSearchCommander].in[AppScoped]
  }
}

class FixedResultSearchCommander extends SearchCommander {
  private val results: Map[String, DecoratedResult] = Map(
    ("test" -> DecoratedResult(
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
          new Scoring( // scoring
            1.3f, // textScore
            1.0f, // normalizedTextScore,
            1.0f, // bookmarkScore
            0.5f, // recencyScore
            false // usefulPage
          )
        ).set("basicUsers", JsArray(Seq(Json.toJson(BasicUser(ExternalId[User]("4e5f7b8c-951b-4497-8661-a1001885b2ec"), "Vorname", "Nachname", "1.jpg", Some(Username("vorname")))))))
      ),
      1, // myTotal
      12, // friendsTotal
      123, // othersTotal
      "test", // query
      Id[User](99), // userId
      Set(100, 220), // idFilter
      false, // mayHaveMoreHits
      true, //show
      Some(Id[SearchConfigExperiment](10)), //searchExperimentId
      Seq.empty[JsObject] // experts
    ))
  )

  def search(
    userId: Id[User],
    acceptLangs: Seq[String],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    predefinedConfig: Option[SearchConfig] = None,
    start: Option[String] = None,
    end: Option[String] = None,
    tz: Option[String] = None,
    coll: Option[String] = None,
    debug: Option[String] = None,
    withUriSummary: Boolean = false): DecoratedResult = {
    results(query)
  }

  def distSearch(
    shards: Set[Shard[NormalizedURI]],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    experiments: Set[ExperimentType],
    query: String,
    filter: Option[String],
    maxHits: Int,
    context: Option[String],
    predefinedConfig: Option[SearchConfig],
    start: Option[String],
    end: Option[String],
    tz: Option[String],
    coll: Option[String],
    debug: Option[String]): PartialSearchResult = ???

  def distLangFreqs(shards: Set[Shard[NormalizedURI]], userId: Id[User]) = ???

  def explain(userId: Id[User], uriId: Id[NormalizedURI], lang: Option[String], experiments: Set[ExperimentType], query: String): Option[(Query, Explanation)] = ???
  def sharingUserInfo(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Seq[SharingUserInfo] = ???
  def warmUp(userId: Id[User]): Unit = {}
}
