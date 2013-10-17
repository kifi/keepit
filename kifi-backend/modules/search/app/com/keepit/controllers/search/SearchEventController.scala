package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.Id
import com.keepit.model.{User, NormalizedURI}
import com.keepit.search.{SearchEnded, ResultClicked, ResultClickTracker}
import play.api.libs.json.{JsObject, JsString, Json}
import play.api.mvc.Action
import com.keepit.heimdal.SearchAnalytics

class SearchEventController @Inject() (resultClickTracker: ResultClickTracker, searchAnalytics: SearchAnalytics) extends SearchServiceController {

  def logResultClicked = Action(parse.json) { request =>
    val resultClicked = Json.fromJson[ResultClicked](request.body).get
    resultClicked.keptUri.map { uriId => resultClickTracker.add(resultClicked.userId, resultClicked.query, uriId, resultClicked.resultPosition, resultClicked.isUserKeep) }
    searchAnalytics.searchResultClicked(resultClicked)
    Ok(JsObject(Seq("stored" -> JsString("ok"))))
  }

  def logSearchEnded = Action(parse.json) { request =>
    val searchEnded = Json.fromJson[SearchEnded](request.body).get
    searchAnalytics.searchEnded(searchEnded)
    Ok(JsObject(Seq("logged" -> JsString("ok"))))
  }
}
