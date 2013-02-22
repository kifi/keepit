package com.keepit.controllers.admin

import com.keepit.common.controller.FortyTwoController
import play.api.mvc.Action
import play.api.Play
import play.api.http.ContentTypes
import play.api.Play.current
import com.keepit.search.phrasedetector.PhraseImporter
import com.keepit.model.PhraseRepo
import com.keepit.inject._
import com.keepit.common.db.slick.DBConnection

object PhraseController extends FortyTwoController  {

  val pageSize = 50

  def displayPhrases(page: Int = 0) = AdminHtmlAction{ implicit request =>
    val phraseRepo = inject[PhraseRepo]
    val (phrasesOpt, count) = inject[DBConnection].readOnly { implicit session =>
      val count = phraseRepo.count
      val phrasesOpt = if(!PhraseImporter.isInProgress) {
        Some(phraseRepo.page(page, pageSize))
      } else {
        None
      }
      (phrasesOpt, count)
    }
    val numPages = (count / pageSize).toInt
    Ok(views.html.phraseManager(phrasesOpt, Nil, page, count, numPages))
  }

  def refreshPhrases = AdminHtmlAction{ implicit request =>
    Ok
  }
  def addPhrase = AdminHtmlAction{ implicit request =>
    Ok
  }
  def savePhrases = AdminHtmlAction{ implicit request =>
    Ok
  }
}
