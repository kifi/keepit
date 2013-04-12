package com.keepit.controllers.admin

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.model.{PhraseStates, PhraseRepo, Phrase}
import com.keepit.search.phrasedetector.PhraseImporter
import com.keepit.search.{SearchServiceClient, Lang}
import views.html

@Singleton
class PhraseController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  phraseRepo: PhraseRepo,
  searchClient: SearchServiceClient)
    extends AdminController(actionAuthenticator) {

  val pageSize = 50

  def displayPhrases(page: Int = 0) = AdminHtmlAction{ implicit request =>
    val (phrasesOpt, count) = db.readOnly { implicit session =>
      val count = 10//phraseRepo.count
      val phrasesOpt = if(!PhraseImporter.isInProgress) {
        Some(phraseRepo.page(page, pageSize))
      } else {
        None
      }
      (phrasesOpt, count)
    }
    val numPages = (count / pageSize).toInt
    Ok(html.admin.phraseManager(phrasesOpt, page, count, numPages))
  }

  def refreshPhrases = AdminHtmlAction{ implicit request =>
    searchClient.refreshPhrases()
    Redirect(com.keepit.controllers.admin.routes.PhraseController.displayPhrases())
  }
  def addPhrase = AdminHtmlAction{ implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val phrase = body.get("phrase").get
    val lang = body.get("lang").get
    val source = body.get("source").get

    db.readWrite { implicit session =>
      phraseRepo.save(Phrase(phrase = phrase, lang = Lang(lang), source = source))
    }
    Redirect(com.keepit.controllers.admin.routes.PhraseController.displayPhrases())
  }

  def savePhrases = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    db.readWrite { implicit session =>
      val repo = phraseRepo
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
