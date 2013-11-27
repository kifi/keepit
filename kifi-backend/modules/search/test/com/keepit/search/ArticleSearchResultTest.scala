package com.keepit.search

import com.keepit.common.db._
import com.keepit.model._
import org.specs2.mutable._
import com.keepit.serializer.ArticleSearchResultSerializer
import com.keepit.test.TestApplication
import play.api.test.Helpers._

class ArticleSearchResultTest extends Specification {

  val initialResult = ArticleSearchResult(
    last = None,
    query = "scala query",
    hits = Seq(ArticleHit(Id[NormalizedURI](1), 0.1F, true, false, Seq(Id[User](33)), 42)),
    myTotal = 4242,
    friendsTotal = 3232,
    othersTotal = 5252,
    mayHaveMoreHits = true,
    scorings = Seq(new Scoring(.2F, .3F, .4F, .5F, true)),
    filter = Set(100L, 200L, 300L),
    uuid = ExternalId[ArticleSearchResult](),
    pageNumber = 3,
    previousHits = 13,
    millisPassed = 23,
    collections = Set(1L,10L,100L),
    svVariance = 1.0f)

  val nextResult = ArticleSearchResult(
    last = Some(initialResult.uuid),
    query = "scala query",
    hits = Seq(ArticleHit(Id[NormalizedURI](1), 0.1F, true, false, Seq(Id[User](33)), 42)),
    myTotal = 4242,
    friendsTotal = 3232,
    othersTotal = 5252,
    mayHaveMoreHits = true,
    scorings = Seq(new Scoring(.2F, .3F, .4F, .5F, true)),
    filter = Set(100L, 200L, 300L),
    uuid = ExternalId[ArticleSearchResult](),
    pageNumber = 3,
    previousHits = 13,
    millisPassed = 23,
    collections = Set(1L,10L,100L),
    svVariance = 1.0f)

  "ArticleSearchResult" should {
    "be serialized" in {

      val serializer = new ArticleSearchResultSerializer()

      val initialJson = serializer.writes(initialResult)
      val initialDeserialized = serializer.reads(initialJson).get
      initialDeserialized.uuid === initialResult.uuid
      initialDeserialized === initialResult

      val nextJson = serializer.writes(nextResult)
      val nextDeserialized = serializer.reads(nextJson).get
      nextDeserialized.uuid === nextResult.uuid
      nextDeserialized === nextResult
    }

    "be stored and retrieved" in {
      running(new TestApplication()) {
        val store = new InMemoryArticleSearchResultStoreImpl()

        store += (initialResult.uuid, initialResult)
        store += (nextResult.uuid, nextResult)

        store.get(initialResult.uuid) === Some(initialResult)
        store.get(nextResult.uuid) === Some(nextResult)

        store.getInitialSearchId(initialResult) === initialResult.uuid
        store.getInitialSearchId(initialResult.uuid) === initialResult.uuid
        store.getInitialSearchId(nextResult) === initialResult.uuid
        store.getInitialSearchId(nextResult.uuid) === initialResult.uuid
      }
    }
  }
}
