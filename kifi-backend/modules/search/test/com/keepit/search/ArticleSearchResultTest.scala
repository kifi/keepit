package com.keepit.search

import com.keepit.common.db._
import com.keepit.model._
import org.specs2.mutable._
import play.api.libs.json.{ JsArray, JsObject, Json }

class ArticleSearchResultTest extends Specification {

  val initialResult = ArticleSearchResult(
    last = None,
    query = "scala query",
    hits = Seq(ArticleHit(Id[NormalizedURI](1), 0.1F, -1.0F, true, false, false, Seq(Id[User](33)), 42)),
    myTotal = 4242,
    friendsTotal = 3232,
    othersTotal = 5252,
    mayHaveMoreHits = true,
    filter = Set(100L, 200L, 300L),
    uuid = ExternalId[ArticleSearchResult](),
    pageNumber = 3,
    previousHits = 13,
    millisPassed = 23,
    toShow = false,
    lang = "fr"
  )

  val nextResult = ArticleSearchResult(
    last = Some(initialResult.uuid),
    query = "scala query",
    hits = Seq(ArticleHit(Id[NormalizedURI](1), 0.1F, -1.0F, true, false, false, Seq(Id[User](33)), 42)),
    myTotal = 4242,
    friendsTotal = 3232,
    othersTotal = 5252,
    mayHaveMoreHits = true,
    filter = Set(100L, 200L, 300L),
    uuid = ExternalId[ArticleSearchResult](),
    pageNumber = 3,
    previousHits = 13,
    millisPassed = 23,
    lang = "fr"
  )

  "ArticleSearchResult" should {
    "be serialized" in {

      val initialJson = Json.toJson(initialResult)
      val initialDeserialized = initialJson.as[ArticleSearchResult]
      initialDeserialized.uuid === initialResult.uuid
      initialDeserialized === initialResult

      val nextJson = Json.toJson(nextResult)
      val nextDeserialized = nextJson.as[ArticleSearchResult]
      nextDeserialized.uuid === nextResult.uuid
      nextDeserialized === nextResult
    }

    "deal with legacy ArticleSearchResult missing toShow" in {
      val fullJson = Json.toJson(initialResult)
      val legacyJson = JsObject((fullJson.as[JsObject].value - "toShow").toSeq)
      val legacyDeserialized = legacyJson.as[ArticleSearchResult]
      legacyDeserialized === initialResult.copy(toShow = true)
    }

    "deal with new ArticleSearchResult missing isPrivate, Users" in {
      val fullJson = Json.toJson(initialResult).as[JsObject]
      val hits = (fullJson \ "hits").as[JsArray].value
      val newHits = JsArray(hits.map(hit => hit.as[JsObject] - "isPrivate" - "Users"))
      val newJson = fullJson - "hits" + ("hits" -> newHits)
      newJson !== fullJson

      val newDeserialized = newJson.as[ArticleSearchResult]
      newDeserialized === initialResult
    }

    "deal with legacy ArticleHit missing textScore" in {
      val fullJson = Json.toJson(initialResult).as[JsObject]
      val hits = (fullJson \ "hits").as[JsArray].value
      val legacyHits = JsArray(hits.map(hit => hit.as[JsObject] - "textScore"))
      val legacyJson = fullJson - "hits" + ("hits" -> legacyHits)
      legacyJson !== fullJson

      val legacyDeserialized = legacyJson.as[ArticleSearchResult]
      legacyDeserialized === initialResult
    }

    "deal with legacy ArticleHit missing keptByFriend" in {
      val fullJson = Json.toJson(initialResult).as[JsObject]
      val hits = (fullJson \ "hits").as[JsArray].value
      val legacyHits = JsArray(hits.map(hit => hit.as[JsObject] - "keptByFriend"))
      val legacyJson = fullJson - "hits" + ("hits" -> legacyHits)
      legacyJson !== fullJson

      val legacyDeserialized = legacyJson.as[ArticleSearchResult]
      legacyDeserialized === initialResult
    }

    "be stored and retrieved" in {
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
