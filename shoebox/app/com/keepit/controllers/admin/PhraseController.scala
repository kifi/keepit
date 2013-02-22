package com.keepit.controllers.admin

import com.keepit.common.controller.FortyTwoController
import play.api.mvc.Action
import play.api.Play
import play.api.http.ContentTypes
import play.api.Play.current
import com.keepit.search.phrasedetector.PhraseImporter

object PhraseController extends FortyTwoController  {

  def displayPhrases(page: Int = 0) = AdminHtmlAction{ implicit request =>
    if(PhraseImporter.isInProgress) {
      
    } else {

    }
    Ok
  }
}
