package com.keepit.search.controllers.ext

import com.keepit.common.crypto.{ FakeCryptoModule, PublicIdConfiguration }
import com.keepit.search.engine.uri.{ UriSearchResult, UriShardHit, UriShardResult }
import com.keepit.search.test.SearchTestInjector
import org.specs2.mutable._
import com.keepit.model._
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller.{ FakeUserActionsHelper, FakeUserActionsModule }
import play.api.test.FakeRequest
import play.api.test.Helpers._
import com.keepit.search._
import com.keepit.common.util.PlayAppConfigurationModule
import com.keepit.search.controllers.{ FixedResultIndexModule, FixedResultUriSearchCommander }

class ExtSearchControllerTest extends Specification with SearchTestInjector {

  def modules = {
    Seq(
      FakeActorSystemModule(),
      FakeUserActionsModule(),
      FixedResultIndexModule(),
      PlayAppConfigurationModule(),
      FakeCryptoModule()
    )
  }

  "ExtSearchController" should {

    "search keeps with library support and JSON response" in {
      withInjector(modules: _*) { implicit injector =>
        implicit val publicIdConfig = inject[PublicIdConfiguration]
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
          "13b\r\n" + // chunk byte count
          s"""
          {
            "uuid":"98765432-1234-5678-9abc-fedcba987654",
            "query":"test",
            "hits":[{"title":"Example Site","url":"http://example.com","keepId":"604754fb-182d-4c39-a314-2d1994b24159","uriId":"${NormalizedURI.publicId(Id(ExtSearchControllerTest.plainTestResults("test").hits.head.id)).id}"}],
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
        implicit val publicIdConfig = inject[PublicIdConfiguration]
        val path = routes.ExtSearchController.search2("test", 2, None, None, None, None, None).url
        path === "/ext/search?q=test&n=2"

        inject[UriSearchCommander].asInstanceOf[FixedResultUriSearchCommander].setPlainResults(ExtSearchControllerTest.plainTestResults)
        val user = UserFactory.user().withId(1).withName("prénom", "nom").withUsername("test").get
        inject[FakeUserActionsHelper].setUser(user)
        val request = FakeRequest("GET", path).withHeaders("Accept" -> "text/plain")
        val result = inject[ExtSearchController].search2("test", 2, None, None, None, None, None)(request)
        status(result) === OK
        contentType(result) === Some("text/plain")
        contentAsString(result) === "13b\r\n" + // chunk byte count
          s"""
          {
            "uuid":"98765432-1234-5678-9abc-fedcba987654",
            "query":"test",
            "hits":[{"title":"Example Site","url":"http://example.com","keepId":"604754fb-182d-4c39-a314-2d1994b24159","uriId":"${NormalizedURI.publicId(Id(ExtSearchControllerTest.plainTestResults("test").hits.head.id)).id}"}],
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
