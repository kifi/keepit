package com.keepit.controllers.search

import com.google.inject.{Inject}
import com.keepit.search.spellcheck.SpellCorrector
import com.keepit.common.controller.SearchServiceController

import play.api.mvc.Action
import play.api.libs.json._



class SpellCorrectorController @Inject() (corrector: SpellCorrector) extends SearchServiceController {

  def correct(input: String) =  Action { request =>
    val suggest = corrector.getSuggestions(input, numSug = 5)
    Ok(Json.obj("correction" -> suggest.mkString("\n")))
  }
}
