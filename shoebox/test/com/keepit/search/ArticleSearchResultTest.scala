package com.keepit.search

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphSearcher
import com.keepit.search.index.ArticleIndexer
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURI.States._
import com.keepit.common.db.{Id, CX, ExternalId}
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.test.EmptyApplication
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import org.apache.lucene.store.RAMDirectory
import scala.math._
import com.keepit.serializer.ArticleSearchResultSerializer

@RunWith(classOf[JUnitRunner])
class ArticleSearchResultTest extends SpecificationWithJUnit {

  "ArticleSearchResult" should {
    "be serialized" in {
      running(new EmptyApplication()) {
         val res = ArticleSearchResult(
              last = Some(ExternalId[ArticleSearchResultRef]()),
              query = "scala query",
              hits = Seq(ArticleHit(Id[NormalizedURI](1), 0.1F, true, false, Set(Id[User](33)), 42)),
              myTotal = 4242,
              friendsTotal = 3232,
              mayHaveMoreHits = true,
              scorings = Seq(new Scoring(.2F, .3F, .4F)),
              userId = Id[User](55),
              uuid = ExternalId[ArticleSearchResultRef]())
         val json = new ArticleSearchResultSerializer().writes(res)
         val deserialized = new ArticleSearchResultSerializer().reads(json)
         deserialized.uuid === res.uuid
         deserialized === res
      }
    }
  }
}