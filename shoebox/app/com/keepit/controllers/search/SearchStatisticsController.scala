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
import play.api.libs.json.JsArray
import com.keepit.serializer.UriLabelSerializer

class SearchStatisticsController @Inject() (sseFactory: Provider[SearchStatisticsExtractorFactory])
  extends SearchServiceController with Logging{

  def getSearchStatistics() = Action(parse.json) { request =>
    val json = request.body
    val queryUUID = (json \ "queryUUID").as[String]
    val queryString = (json \ "queryString").as[String]
    val userId = (json \ "userId").as[Long]
    val uriIds = (json \ "uriIds").as[JsArray].value.map{id => Id[NormalizedURI](id.asOpt[Long].get)}
    val labels = (json \ "uriLabels").as[JsArray].value.map{js => UriLabelSerializer.serializer.reads(js).get}
    val labeledUris = (uriIds zip labels).foldLeft(Map.empty[Id[NormalizedURI], UriLabel]){ case (m , pair) => m + (pair._1 -> pair._2) }
    val sse = sseFactory.get.apply(ExternalId[ArticleSearchResultRef](queryUUID), queryString, Id[User](userId), labeledUris)
    val searchStatistics = sse.getSearchStatistics(labeledUris.keySet)
    val statistics = searchStatistics.map(_._2).map(x => SearchStatisticsSerializer.serializer.writes(x)).toList
    Ok(JsArray(statistics))
  }
}
