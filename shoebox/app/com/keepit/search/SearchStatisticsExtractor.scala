package com.keepit.search

import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.model.NormalizedURI

case class BasicQueryInfo(
  queryUUID: ExternalId[ArticleSearchResultRef],
  queryString: String,
  userId: Id[User])

case class UriInfo(
  uriId: Id[NormalizedURI],
  textScore: Float,
  normalizedTextScore: Float,
  bookmarkScore: Float,             // raw score only. Boosted score depends on SearchConfig, which varies from time to time.
  recencyScore: Float,
  clickBoost: Float,
  isMyBookmark: Boolean,            // myBookmark is a.k.a. my keep
  isPrivate: Boolean,               // private for this user
  friendsKeepsCount: Int,           // num of keeps of this uri among my friends
  totalCounts: Int,                 // num of keeps of this uri in entire index
  incorrectlyRanked: Boolean        // this is true only in this scenario: if this uri is shown to user, AND not clicked by user, AND user clicks some url from google, AND that url is indexed by KiFi.
  )

case class SearchResultInfo(
  myHits: Int,
  friendsHits: Int,
  othersHits: Int,
  svVariance: Float,
  svExistenceVar: Float)

// the structure may change a lot in the future,
// as new queries are added and old queries are discarded.
case class LuceneScores(
  multiplicativeBoost: Float,
  additiveBoost: Float,
  percentMatch: Float,
  semanticVector: Float,
  phraseProximity: Float)

case class SearchStatistics(
  basicQueryInfo: BasicQueryInfo,
  uriInfo: UriInfo,
  searchResultInfo: SearchResultInfo,
  luceneScores: LuceneScores)

class SearchStatisticsExtractor(val queryUUID: ExternalId[ArticleSearchResultRef],
  val queryString: String, val userId: Id[User], val uriId: Id[NormalizedURI]) {

  def getBasicQueryInfo = BasicQueryInfo(queryUUID, queryString, userId)

  def getUriInfo = {

  }
  def getSearchResultInfo = {

  }
  def getLuceneScores = {

  }
  def getSearchStatistics = {

  }
}
