package com.keepit.controllers.admin

import com.google.inject.Inject

import com.keepit.common.controller.{ AdminController, UserActionsHelper, AdminUserActions }
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.model.{ PhraseStates, PhraseRepo, Phrase }
import com.keepit.shoebox.PhraseImporter
import com.keepit.search.{ SearchServiceClient, Lang }
import views.html

class PhraseController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  phraseRepo: PhraseRepo,
  searchClient: SearchServiceClient)
    extends AdminUserActions {

  val pageSize = 50

  def displayPhrases(page: Int = 0) = AdminUserPage { implicit request =>
    val (phrasesOpt, count) = db.readOnlyReplica { implicit session =>
      val count = 10 //phraseRepo.count
      val phrasesOpt = if (!PhraseImporter.isInProgress) {
        Some(phraseRepo.page(page, pageSize))
      } else {
        None
      }
      (phrasesOpt, count)
    }
    val numPages = (count / pageSize).toInt
    Ok(html.admin.phraseManager(phrasesOpt, page, count, numPages))
  }

  def refreshPhrases = AdminUserPage { implicit request =>
    searchClient.refreshPhrases()
    Redirect(com.keepit.controllers.admin.routes.PhraseController.displayPhrases())
  }
  def addPhrase = AdminUserPage { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val phrase = body.get("phrase").get
    val lang = body.get("lang").get
    val source = body.get("source").get

    db.readWrite { implicit session =>
      phraseRepo.save(Phrase(phrase = phrase, lang = Lang(lang), source = source))
    }
    Redirect(com.keepit.controllers.admin.routes.PhraseController.displayPhrases())
  }

  def savePhrases = AdminUserPage { implicit request =>
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
