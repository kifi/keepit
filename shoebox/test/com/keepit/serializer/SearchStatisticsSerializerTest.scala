package com.keepit.serializer

import org.specs2.mutable.Specification
import com.keepit.search.BasicQueryInfo
import com.keepit.common.db.ExternalId
import com.keepit.search.ArticleSearchResultRef
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.UriInfo
import com.keepit.model.NormalizedURI
import com.keepit.search.SearchResultInfo
import com.keepit.search.LuceneScores
import com.keepit.search.SearchStatistics
import com.keepit.search.UriClickInfo

class SearchStatisticsSerializerTest extends Specification {
  "SearchStatistics Serializer" should {
    "correctly serialize" in {
      val qInfo = BasicQueryInfo(queryUUID = ExternalId[ArticleSearchResultRef]("87aea853-abe9-4889-81b6-6ca3f1cc7a6b"),
        queryString = "sample query", userId = Id[User](1L))

      val uriInfo = UriInfo(uriId = Id[NormalizedURI](2L), textScore = 1.0f, bookmarkScore = 3.0f,
        recencyScore = 4.0f, clickBoost = 5.0f, isMyBookmark = false, isPrivate = false,
        friendsKeepsCount = 1, totalCounts = 7)

      val uriClickInfo = UriClickInfo(true, false)

      val sInfo = SearchResultInfo(myHits = 3, friendsHits = 5, othersHits = 7, svVariance = 0.18f, svExistenceVar = 0.34f)

      val lscore = LuceneScores(multiplicativeBoost = 0.12f, additiveBoost = 0.23f, percentMatch = 0.34f, semanticVector = 0.45f, phraseProximity = 0.56f)

      val searchStat = SearchStatistics(qInfo, uriInfo, uriClickInfo, sInfo, lscore)

      val json = SearchStatisticsSerializer.serializer.writes(searchStat)
      SearchStatisticsSerializer.serializer.reads(json).get === searchStat

    }
  }
}
