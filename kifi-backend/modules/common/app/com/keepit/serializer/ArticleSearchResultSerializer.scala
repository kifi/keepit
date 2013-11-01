package com.keepit.serializer

import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.time._
import play.api.libs.json._
import com.keepit.search.ArticleSearchResult
import com.keepit.search.ArticleHit
import com.keepit.model.User
import org.joda.time.DateTime
import com.keepit.search.Lang

class ArticleSearchResultSerializer extends Format[ArticleSearchResult] {

  def writes(res: ArticleSearchResult): JsValue =
    JsObject(List(
        "last" -> (res.last map { t => JsString(t.id) } getOrElse(JsNull)),
        "query" -> JsString(res.query),
        "lang" -> JsString(res.lang.lang),
        "hits" -> JsArray(res.hits map writeHit),
        "myTotal" -> JsNumber(res.myTotal),
        "friendsTotal" -> JsNumber(res.friendsTotal),
        "mayHaveMoreHits" -> JsBoolean(res.mayHaveMoreHits),
        "scorings" -> JsArray(res.scorings map ScoringSerializer.scoringSerializer.writes),
        "filter" -> JsArray(res.filter.map(id => JsNumber(id)).toSeq),
        "uuid" -> JsString(res.uuid.id),
        "time" -> Json.toJson(res.time),
        "millisPassed" -> JsNumber(res.millisPassed),
        "pageNumber" -> JsNumber(res.pageNumber),
        "collections" -> JsArray(res.collections.map(id => JsNumber(id)).toSeq),
        "svVariance" ->  JsNumber(res.svVariance),
        "svExistenceVar" -> JsNumber(res.svExistenceVar)
      )
    )

  def writeHit(hit: ArticleHit): JsValue = JsObject(List(
        "uriId" -> JsNumber(hit.uriId.id),
        "score" -> JsNumber(hit.score),
        "isMyBookmark" -> JsBoolean(hit.isMyBookmark),
        "isPrivate" -> JsBoolean(hit.isPrivate),
        "users" -> JsArray(writeUserId(hit.users.toSeq)),
        "bookmarkCount" -> JsNumber(hit.bookmarkCount)
      ))

  def writeUserId(users: Seq[Id[User]]): Seq[JsValue] = users map {u => JsNumber(u.id)}

  def readHits(jsonArray: JsArray): Seq[ArticleHit] = jsonArray.value map { json =>
    ArticleHit(
      Id((json \ "uriId").as[Long]),
      (json \ "score").as[Float],
      (json \ "isMyBookmark").as[Boolean],
      (json \ "isPrivate").as[Boolean],
      (json \ "users").as[Seq[Long]].map(Id[User](_)),
      (json \ "bookmarkCount").as[Int]
    )
  }

  def reads(json: JsValue): JsResult[ArticleSearchResult] = JsSuccess(ArticleSearchResult(
      last = (json \ "last").asOpt[String] map (j => ExternalId[ArticleSearchResult](j)),
      query = (json \ "query").as[String],
      lang = (json \ "lang").asOpt[String].map(lang => Lang(lang)).getOrElse(Lang("en")),
      hits = readHits((json \ "hits").asInstanceOf[JsArray]),
      myTotal = (json \ "myTotal").as[Int],
      friendsTotal = (json \ "friendsTotal").as[Int],
      mayHaveMoreHits = (json \ "mayHaveMoreHits").as[Boolean],
      scorings = (json \ "scorings").asInstanceOf[JsArray].value map (s => ScoringSerializer.scoringSerializer.reads(s).get),
      filter = (json \ "filter").asOpt[Seq[Long]].map(_.toSet).getOrElse(Set.empty[Long]),
      uuid = ExternalId[ArticleSearchResult]((json \ "uuid").as[String]),
      time = (json \ "time").as[DateTime],
      millisPassed = (json \ "millisPassed").as[Int],
      pageNumber = (json \ "pageNumber").as[Int],
      collections = (json \ "collections").asOpt[Seq[Long]].getOrElse(Seq.empty[Long]).toSet,
      svVariance = (json \ "svVariance").asOpt[Float].getOrElse(-1.0f),
      svExistenceVar = (json \ "svExistenceVar").asOpt[Float].getOrElse(-1.0f)
    ))
}

object ArticleSearchResultSerializer {
  implicit val articleSearchResultSerializer = new ArticleSearchResultSerializer
}
