package com.keepit.search

import com.google.inject.{Inject, Singleton}
import com.google.inject.Provider
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.common.db.ExternalId
import com.keepit.model.User
import com.keepit.model.NormalizedURI

@Singleton
class SearchResultClassifierFactory @Inject() (sseFactory: Provider[SearchStatisticsExtractorFactory]){
  def apply(queryUUID: ExternalId[ArticleSearchResultRef], queryString: String, userId: Id[User], uriIds: Seq[Id[NormalizedURI]]) = {
    val uriLabelMap =  uriIds.foldLeft(Map.empty[Id[NormalizedURI], UriLabel]){ (map, uri) => map + (uri -> UriLabel(false, false)) }  // label is dummy. not used. Probably it's better to have some API refactor.
    val sse = sseFactory.get().apply(queryUUID, queryString, userId, uriLabelMap)
    new SearchResultClassifier(sse)
  }
}

class SearchResultClassifier(sse: SearchStatisticsExtractor){
  def classify(uriId: Id[NormalizedURI]) = {
    val uriInfo = sse.getUriInfo(uriId)
    val score = sse.getLuceneScores(uriId)
    if (score.semanticVector >= 0.3f || uriInfo.clickBoost > 2.5f) true else false
  }
}
