package com.keepit.search

import com.keepit.common.db._
import com.keepit.model._
import com.keepit.inject._
import com.keepit.test._
import org.specs2.mutable._
import play.api.test.Helpers._
import com.keepit.serializer.ArticleSearchResultSerializer
import com.google.inject.Injector

class ArticleSearchResultTest extends Specification with ShoeboxTestInjector {

  "ArticleSearchResult" should {
    "be serialized" in {
      withDb() { implicit injector: Injector =>
         val res = ArticleSearchResult(
              last = Some(ExternalId[ArticleSearchResult]()),
              query = "scala query",
              hits = Seq(ArticleHit(Id[NormalizedURI](1), 0.1F, true, false, Seq(Id[User](33)), 42)),
              myTotal = 4242,
              friendsTotal = 3232,
              mayHaveMoreHits = true,
              scorings = Seq(new Scoring(.2F, .3F, .4F, .5F, true)),
              filter = Set(100L, 200L, 300L),
              uuid = ExternalId[ArticleSearchResult](),
              pageNumber = 3,
              millisPassed = 23,
              collections = Set(1L,10L,100L),
              svVariance = 1.0f)
         val json = new ArticleSearchResultSerializer().writes(res)
         val deserialized = new ArticleSearchResultSerializer().reads(json).get
         deserialized.uuid === res.uuid
         deserialized === res
      }
    }
  }
}
