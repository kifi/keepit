package com.keepit.controllers.search

import com.google.inject.{ Inject }
import com.keepit.search.spellcheck.SpellCorrector
import com.keepit.common.controller.SearchServiceController

import play.api.mvc.Action
import play.api.libs.json._

class SpellCorrectorController @Inject() (corrector: SpellCorrector) extends SearchServiceController {

  def correct(input: String, enableBoost: Boolean) = Action { request =>
    val suggests = corrector.getScoredSuggestions(input, numSug = 5, enableBoost)
    Ok(JsArray(suggests.map { s => Json.toJson(s) }))
  }
}
