package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.search.query.parser.SpellCorrector
import com.keepit.common.controller.SearchServiceController
import play.api.mvc.Action
import play.api.libs.json.Json


class SpellCorrectorController @Inject()(spellCorrector: SpellCorrector) extends SearchServiceController{
  def buildDictionary() = Action { request =>
    spellCorrector.buildDictionary()
    Ok("spell-corrector dictionary is built")
  }

  def correctSpelling(text: String) = Action { request =>
    val s = spellCorrector.getAlternativeQuery(text)
    Ok(Json.obj("correction"->s))
  }

  def getBuildStatus() = Action { request =>
    val s = spellCorrector.getBuildingStatus
    Ok(s.toString)
  }
}
