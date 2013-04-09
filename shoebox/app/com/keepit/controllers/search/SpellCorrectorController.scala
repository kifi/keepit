package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.search.query.parser.SpellCorrector
import com.keepit.common.controller.SearchServiceController
import play.api.mvc.Action


class SpellCorrectorController @Inject()(spellCorrector: SpellCorrector) extends SearchServiceController{
  def buildDictionary() = Action { request =>
    spellCorrector.buildDictionary()
    Ok("spell-corrector dictionary is built")
  }

  def getBuildStatus() = Action{ request =>
    val s = spellCorrector.getBuildingStatus
    Ok(s.toString)
  }
}
