package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.Id
import com.keepit.model.{User, NormalizedURI}
import com.keepit.search.{ResultClicked, ResultClickTracker}
import play.api.libs.json.{JsObject, JsString, Json}
import play.api.mvc.Action

class SearchEventController @Inject()(
	resultClickTracker: ResultClickTracker) 
  extends SearchServiceController {

  def logResultClicked = Action(parse.json) { request =>
    val rc = Json.fromJson[ResultClicked](request.body).get
    resultClickTracker.add(rc.userId, rc.query, rc.uriId, rc.rank, rc.isUserKeep)
    Ok(JsObject(Seq("stored" -> JsString("ok"))))
  }
}
