package com.keepit.serializer

import com.keepit.search.BasicQueryInfo
import play.api.libs.json._
import com.keepit.common.db.ExternalId
import com.keepit.search.ArticleSearchResultRef
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.UriInfo
import com.keepit.model.NormalizedURI
import com.keepit.search.SearchResultInfo
import com.keepit.search.LuceneScores
import com.keepit.search.SearchStatistics

class BasicQueryInfoSerializer extends Format[BasicQueryInfo] {
  def writes(info: BasicQueryInfo): JsValue = {
    Json.obj("queryUUID" -> JsString(info.queryUUID.toString),
      "queryString" -> JsString(info.queryString),
      "userId" -> JsString(info.userId.toString))
  }

  def reads(json: JsValue): JsResult[BasicQueryInfo] = {
    JsSuccess(BasicQueryInfo(
      queryUUID = ExternalId[ArticleSearchResultRef]((json \ "queryUUID").asOpt[String].getOrElse("")),
      queryString = (json \ "queryString").asOpt[String].getOrElse(""),
      userId = Id[User]((json \ "userId").asOpt[String].getOrElse("0").toLong)))
  }
}

object BasicQueryInfoSerializer {
  implicit val serializer = new BasicQueryInfoSerializer
}

class UriInfoSerializer extends Format[UriInfo] {
  def writes(info: UriInfo): JsValue = {
    Json.obj("uriId" -> JsString(info.uriId.toString),
      "textScore" -> JsNumber(info.textScore),
      "normalizedTextScore" -> JsNumber(info.normalizedTextScore),
      "bookmarkScore" -> JsNumber(info.bookmarkScore),
      "recencyScore" -> JsNumber(info.recencyScore),
      "clickBoost" -> JsNumber(info.clickBoost),
      "isMyBookmark" -> JsBoolean(info.isMyBookmark),
      "isPrivate" -> JsBoolean(info.isPrivate),
      "friendsKeepsCount" -> JsNumber(info.friendsKeepsCount),
      "totalCounts" -> JsNumber(info.totalCounts),
      "incorrectlyRanked" -> JsBoolean(info.incorrectlyRanked))
  }

  def reads(json: JsValue): JsResult[UriInfo] = {
    JsSuccess(UriInfo(
      uriId = Id[NormalizedURI]((json \ "uriId").asOpt[String].getOrElse("0").toLong),
      textScore = (json \ "textScore").asOpt[Float].getOrElse(-1.0f),
      normalizedTextScore = (json \ "normalizedTextScore").asOpt[Float].getOrElse(-1.0f),
      bookmarkScore = (json \ "bookmarkScore").asOpt[Float].getOrElse(-1.0f),
      recencyScore = (json \ "recencyScore").asOpt[Float].getOrElse(-1.0f),
      clickBoost = (json \ "clickBoost").asOpt[Float].getOrElse(-1.0f),
      isMyBookmark = (json \ "isMyBookmark").asOpt[Boolean].getOrElse(false),
      isPrivate = (json \ "isPrivate").asOpt[Boolean].getOrElse(false),
      friendsKeepsCount = (json \ "friendsKeepsCount").asOpt[Int].getOrElse(-1),
      totalCounts = (json \ "totalCounts").asOpt[Int].getOrElse(-1),
      incorrectlyRanked = (json \ "incorrectlyRanked").asOpt[Boolean].getOrElse(false)))
  }
}

object UriInfoSerializer {
  implicit val serializer = new UriInfoSerializer
}

class SearchResultInfoSerializer extends Format[SearchResultInfo] {
  def writes(info: SearchResultInfo): JsValue = {
    Json.obj("myHits" -> JsNumber(info.myHits),
      "friendsHits" -> JsNumber(info.friendsHits),
      "othersHits" -> JsNumber(info.othersHits),
      "svVariance" -> JsNumber(info.svVariance),
      "svExistenceVar" -> JsNumber(info.svExistenceVar))
  }

  def reads(json: JsValue): JsResult[SearchResultInfo] = {
    JsSuccess(SearchResultInfo(
      myHits = (json \ "myHits").asOpt[Int].getOrElse(-1),
      friendsHits = (json \ "friendsHits").asOpt[Int].getOrElse(-1),
      othersHits = (json \ "othersHits").asOpt[Int].getOrElse(-1),
      svVariance = (json \ "svVariance").asOpt[Float].getOrElse(-1.0f),
      svExistenceVar = (json \ "svExistenceVar").asOpt[Float].getOrElse(-1.0f)))
  }
}

object SearchResultInfoSerializer {
  implicit val serializer = new SearchResultInfoSerializer
}

class LuceneScoresSerializer extends Format[LuceneScores] {
  def writes(score: LuceneScores): JsValue = {
    Json.obj("multiplicativeBoost" -> JsNumber(score.multiplicativeBoost),
      "additiveBoost" -> JsNumber(score.additiveBoost),
      "percentMatch" -> JsNumber(score.percentMatch),
      "semanticVector" -> JsNumber(score.semanticVector),
      "phraseProximity" -> JsNumber(score.phraseProximity))
  }

  def reads(json: JsValue): JsResult[LuceneScores] = {
    JsSuccess(LuceneScores(
      multiplicativeBoost = (json \ "multiplicativeBoost").asOpt[Float].getOrElse(-1.0f),
      additiveBoost = (json \ "additiveBoost").asOpt[Float].getOrElse(-1.0f),
      percentMatch = (json \ "percentMatch").asOpt[Float].getOrElse(-1.0f),
      semanticVector = (json \ "semanticVector").asOpt[Float].getOrElse(-1.0f),
      phraseProximity = (json \ "phraseProximity").asOpt[Float].getOrElse(-1.0f)))
  }
}

object LuceneScoresSerializer {
  implicit val serializer = new LuceneScoresSerializer
}

class SearchStatisticsSerializer extends Format[SearchStatistics] {
  def writes(stat: SearchStatistics): JsValue = {
    Json.obj("basicQueryInfo" -> BasicQueryInfoSerializer.serializer.writes(stat.basicQueryInfo),
      "uriInfo" -> UriInfoSerializer.serializer.writes(stat.uriInfo),
      "searchResultInfo" -> SearchResultInfoSerializer.serializer.writes(stat.searchResultInfo),
      "luceneScores" -> LuceneScoresSerializer.serializer.writes(stat.luceneScores))
  }

  def reads(json: JsValue): JsResult[SearchStatistics] = {
    val basicQueryInfo = BasicQueryInfoSerializer.serializer.reads(json \ "basicQueryInfo").get
    val uriInfo = UriInfoSerializer.serializer.reads(json \ "uriInfo").get
    val searchResultInfo = SearchResultInfoSerializer.serializer.reads(json \ "searchResultInfo").get
    val luceneScores = LuceneScoresSerializer.serializer.reads(json \ "luceneScores").get
    JsSuccess(SearchStatistics(basicQueryInfo, uriInfo, searchResultInfo, luceneScores))
  }
}

object SearchStatisticsSerializer {
  implicit val serializer = new SearchStatisticsSerializer
}
