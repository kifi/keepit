package com.keepit.controllers.admin

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model.NormalizedURIStates._
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import play.api.libs.json.{ JsNumber, JsObject }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.google.inject.Inject

class AdminArticleIndexerController @Inject() (
    val userActionsHelper: UserActionsHelper,
    searchClient: SearchServiceClient,
    db: Database,
    normUriRepo: NormalizedURIRepo) extends AdminUserActions {

  def index = AdminUserPage { implicit request =>
    searchClient.index()
    Ok("indexed articles")
  }

  def reindex = AdminUserPage { implicit request =>
    searchClient.reindex
    Ok("reindexing started")
  }

  def getSequenceNumber = AdminUserAction.async { implicit request =>
    searchClient.articleIndexerSequenceNumber().map { number =>
      Ok(JsObject(Seq("sequenceNumber" -> JsNumber(number))))
    }
  }

  def refreshSearcher = AdminUserPage { implicit request =>
    searchClient.refreshSearcher()
    Ok("searcher refreshed")
  }

  def dumpLuceneDocument(id: Id[NormalizedURI], deprecated: Boolean) = AdminUserPage.async { implicit request =>
    searchClient.dumpLuceneDocument(id, deprecated).map(Ok(_))
  }
}

