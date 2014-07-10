package com.keepit.controllers.admin

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model.NormalizedURIStates._
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import play.api.libs.json.{ JsNumber, JsObject }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.google.inject.Inject

class AdminArticleIndexerController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    searchClient: SearchServiceClient,
    db: Database,
    normUriRepo: NormalizedURIRepo) extends AdminController(actionAuthenticator) {

  def index = AdminHtmlAction.authenticated { implicit request =>
    searchClient.index()
    Ok("indexed articles")
  }

  def reindex = AdminHtmlAction.authenticated { implicit request =>
    searchClient.reindex
    Ok("reindexing started")
  }

  def getSequenceNumber = AdminJsonAction.authenticatedAsync { implicit request =>
    searchClient.articleIndexerSequenceNumber().map { number =>
      Ok(JsObject(Seq("sequenceNumber" -> JsNumber(number))))
    }
  }

  def refreshSearcher = AdminHtmlAction.authenticated { implicit request =>
    searchClient.refreshSearcher()
    Ok("searcher refreshed")
  }

  def dumpLuceneDocument(id: Id[NormalizedURI]) = AdminHtmlAction.authenticatedAsync { implicit request =>
    searchClient.dumpLuceneDocument(id).map(Ok(_))
  }
}

