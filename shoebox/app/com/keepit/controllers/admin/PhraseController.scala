package com.keepit.controllers.admin

import com.keepit.common.controller.FortyTwoController
import play.api.mvc.Action
import play.api.Play
import play.api.http.ContentTypes
import play.api.Play.current
import com.keepit.search.phrasedetector.{PhraseIndexer, PhraseImporter}
import com.keepit.model.{PhraseStates, PhraseRepo, Phrase}
import com.keepit.inject._
import com.keepit.common.db.slick.DBConnection
import com.keepit.search.Lang
import com.keepit.common.db._

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
    Ok(views.html.phraseManager(phrasesOpt, page, count, numPages))
  }

  def refreshPhrases = AdminHtmlAction{ implicit request =>
    inject[PhraseIndexer].reload()
    Redirect(com.keepit.controllers.admin.routes.PhraseController.displayPhrases())
  }
  def addPhrase = AdminHtmlAction{ implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val phrase = body.get("phrase").get
    val lang = body.get("lang").get
    val source = body.get("source").get

    inject[DBConnection].readWrite { implicit session =>
      inject[PhraseRepo].save(Phrase(phrase = phrase, lang = Lang(lang), source = source))
    }
    Redirect(com.keepit.controllers.admin.routes.PhraseController.displayPhrases())
  }

  def savePhrases = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    inject[DBConnection].readWrite { implicit session =>
      val repo = inject[PhraseRepo]
      for (key <- body.keys.filter(_.startsWith("phrase_")).map(_.substring(7))) {
        val id = Id[Phrase](key.toLong)
        val elem = repo.get(id)
        body.get("active_" + key) match {
          case Some(on) if elem.state != PhraseStates.ACTIVE =>
            repo.save(elem.withState(PhraseStates.ACTIVE))
          case None if elem.state != PhraseStates.INACTIVE =>
            repo.save(elem.withState(PhraseStates.INACTIVE))
          case _ =>
        }
      }
    }
    Redirect(routes.PhraseController.displayPhrases())
  }
}
