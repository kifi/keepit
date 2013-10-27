package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.Id
import com.keepit.model.{User, NormalizedURI}
import com.keepit.search._
import play.api.libs.json._
import play.api.mvc.Action
import com.keepit.heimdal.{SearchEngine, Kifi, SearchAnalytics}
import com.keepit.search.BrowsedURI
import com.keepit.search.ClickedURI

class SearchEventController @Inject() (
  clickHistoryTracker: ClickHistoryTracker,
  browsingHistoryTracker: BrowsingHistoryTracker,
  resultClickedTracker: ResultClickTracker,
  searchAnalytics: SearchAnalytics) extends SearchServiceController {

  def logResultClicked = Action(parse.json) { request =>
    val resultClicked = Json.fromJson[ResultClicked](request.body).get
    resultClicked.keptUri.map { uriId =>
      clickHistoryTracker.add(resultClicked.userId, ClickedURI(uriId))
      resultClickedTracker.add(resultClicked.userId, resultClicked.query, uriId, resultClicked.resultPosition, resultClicked.isUserKeep)
    }
    val (userId, queryUUID, searchExperiment, resultPosition, kifiResults, time) =
      (resultClicked.userId, resultClicked.queryUUID, resultClicked.searchExperiment, resultClicked.resultPosition, resultClicked.kifiResults, resultClicked.time)
    SearchEngine.get(resultClicked.resultSource) match {
      case Kifi => searchAnalytics.kifiResultClicked(userId, queryUUID, searchExperiment, resultPosition, None, None, resultClicked.isUserKeep, None, kifiResults, None, time)
      case theOtherGuys => searchAnalytics.searchResultClicked(userId, queryUUID, searchExperiment, theOtherGuys, resultPosition, kifiResults, None, time)
    }
    Ok
  }

  def logSearchEnded = Action(parse.json) { request =>
    val searchEnded = Json.fromJson[SearchEnded](request.body).get
    searchAnalytics.searchEnded(searchEnded.userId, searchEnded.queryUUID, searchEnded.searchExperiment, searchEnded.kifiResults, searchEnded.kifiResultsClicked, searchEnded.googleResultsClicked, None, searchEnded.time)
    Ok
  }

  def logBrowsingHistory(userId: Id[User]) = Action(parse.json) { request =>
    val browsedUris = request.body.as[JsArray].value.map(Id.format[NormalizedURI].reads)
    browsedUris.foreach(uriIdJs => browsingHistoryTracker.add(userId, BrowsedURI(uriIdJs.get)))
    Ok
  }
}
