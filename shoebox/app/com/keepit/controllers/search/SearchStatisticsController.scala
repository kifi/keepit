package com.keepit.controllers.search

import com.keepit.common.controller.SearchServiceController
import com.keepit.search.SearchStatisticsExtractorFactory
import com.google.inject.Inject
import com.google.inject.Provider
import com.keepit.common.analytics.PersistEventPlugin
import com.keepit.common.db.ExternalId
import com.keepit.model.User
import play.api.libs.json.JsObject
import com.keepit.common.db.slick.Database
import com.keepit.search.ArticleSearchResultRef
import com.keepit.model.NormalizedURIRepo
import com.keepit.model.UserRepo
import com.keepit.search.TrainingDataLabeler
import com.keepit.common.analytics.Events
import com.keepit.common.analytics.EventFamilies
import com.keepit.serializer.SearchStatisticsSerializer
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.search.UriLabel
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import play.api.mvc.Action
import com.keepit.common.logging.Logging

class SearchStatisticsController @Inject() (db: Database,
  userRepo: UserRepo,
  normalizedURIRepo: NormalizedURIRepo,
  sseFactory: Provider[SearchStatisticsExtractorFactory],
  persistEventProvider: Provider[PersistEventPlugin],
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
  extends SearchServiceController with Logging{

  def persistSearchStatistics() = Action(parse.json) { request =>
    val json = request.body

    val queryUUID = (json \ "queryUUID").as[String]
    val queryString = (json \ "query").as[String]
    val userId = (json \ "userId").as[Long]
    val kifiClicked = (json \ "kifiClicked").as[Seq[Long]].map { id => Id[NormalizedURI](id) }
    val googleClicked = (json \ "googleClicked").as[Seq[Long]].map { id => Id[NormalizedURI](id) }
    val kifiShown = (json \ "kifiShown").as[Seq[Long]].map { id => Id[NormalizedURI](id) }

    val data = TrainingDataLabeler.getLabeledData(kifiClicked, googleClicked, kifiShown)
    if (data.nonEmpty) {
      val uriLabel = data.foldLeft(Map.empty[Id[NormalizedURI], UriLabel]) {
        case (m, (id, (isClicked, isCorrectlyRanked))) => m + (id -> UriLabel(isClicked, isCorrectlyRanked))
      }

      val sse = sseFactory.get.apply(ExternalId[ArticleSearchResultRef](queryUUID), queryString, Id[User](userId), uriLabel)
      log.info("search statistics controller: fetching data ...")
      val searchStatistics = sse.getSearchStatistics(uriLabel.keySet)
      log.info("search statistics controller: fetched all data !!! start persisting data ...")
      for (ss <- searchStatistics) {
        val event = Events.serverEvent(EventFamilies.SERVER_SEARCH, "search_statistics", SearchStatisticsSerializer.serializer.writes(ss._2).as[JsObject])
        persistEventProvider.get.persist(event)
      }
      log.info("search statistics controller: all data persisted !!!")
    }
    Ok("search statistics persisted")

  }
}
