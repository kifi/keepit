package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.Id
import com.keepit.model.{User, NormalizedURI}
import com.keepit.search._
import play.api.libs.json._
import play.api.mvc.Action
import com.keepit.heimdal.SearchAnalytics
import com.keepit.search.BrowsedURI
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import com.keepit.search.ClickedURI

class SearchEventController @Inject() (clickHistoryTracker: ClickHistoryTracker, browsingHistoryTracker: BrowsingHistoryTracker, resultClickedTracker: ResultClickTracker, searchAnalytics: SearchAnalytics) extends SearchServiceController {

  def logResultClicked = Action(parse.json) { request =>
    val resultClicked = Json.fromJson[ResultClicked](request.body).get
    resultClicked.keptUri.map { uriId =>
      clickHistoryTracker.add(resultClicked.userId, ClickedURI(uriId))
      resultClickedTracker.add(resultClicked.userId, resultClicked.query, uriId, resultClicked.resultPosition, resultClicked.isUserKeep)
    }
    searchAnalytics.searchResultClicked(resultClicked)
    Ok(JsObject(Seq("stored" -> JsString("ok"))))
  }

  def logSearchEnded = Action(parse.json) { request =>
    val searchEnded = Json.fromJson[SearchEnded](request.body).get
    searchAnalytics.searchEnded(searchEnded)
    Ok(JsObject(Seq("logged" -> JsString("ok"))))
  }

  def logBrowsed(userId: Id[User]) = Action(parse.json) { request =>
    val browsed = request.body.as[JsArray].value.map(Id.format[NormalizedURI].reads)
    browsed.foreach(uriIdJs => browsingHistoryTracker.add(userId, BrowsedURI(uriIdJs.get)))
    Ok(JsObject(Seq("logged" -> JsString("ok"))))
  }
}
