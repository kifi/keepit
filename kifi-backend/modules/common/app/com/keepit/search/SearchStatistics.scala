package com.keepit.search

import com.keepit.common.db.{Id, ExternalId}
import com.keepit.model.{NormalizedURI, User}
import com.keepit.common.logging.Logging
import scala.collection.mutable.Map

case class BasicQueryInfo(
                           queryUUID: ExternalId[ArticleSearchResultRef],
                           queryString: String,
                           userId: Id[User])

// kifi uri only
case class UriInfo(
                    uriId: Id[NormalizedURI],
                    textScore: Float,
                    bookmarkScore: Float, // use raw score only. Boosted score depends on SearchConfig, which varies from time to time.
                    recencyScore: Float,
                    clickBoost: Float,
                    isMyBookmark: Boolean, // myBookmark is a.k.a. my keep
                    isPrivate: Boolean, // private for this user
                    friendsKeepsCount: Int, // num of keeps of this uri among my friends
                    totalCounts: Int // num of keeps of this uri in entire index
                    )



// get this from EventListener. kifi uri only
case class UriLabel(
  clicked: Boolean,
  isCorrectlyRanked: Boolean    // this is false only in this scenario: if this uri was shown to user, AND not clicked by user, AND user clicked some url from google, AND that url is indexed by KiFi.
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
  uriLabel: UriLabel,
  searchResultInfo: SearchResultInfo,
  luceneScores: LuceneScores)

object TrainingDataLabeler extends Logging{
  private val topNkifi = 2      // at most the top 2 kifi results would be labeled as negative samples

  def getLabeledData(kifiClicked: Seq[Id[NormalizedURI]], googleClicked: Seq[Id[NormalizedURI]], kifiShown: Seq[Id[NormalizedURI]]) = {
    var data = Map.empty[Id[NormalizedURI], (Boolean, Boolean)]         // (isPositive, isCorrectlyRanked)
    if (kifiClicked.nonEmpty) {
      kifiClicked.foreach( uri => data += uri -> (true, true))
      log.info("positive data collected")
    } else {
      val isCorrectlyRanked = googleClicked.isEmpty || googleClicked.exists(uri => kifiShown.contains(uri))         // true if the clicked google uri is not indexed, or it was shown to the user
      kifiShown.take(topNkifi).foreach( uri => if (!googleClicked.contains(uri)) data += uri -> (false, isCorrectlyRanked))
      if (data.nonEmpty) log.info("negative data collected")
    }
    data
  }
}
