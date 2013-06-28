package com.keepit.controllers.admin

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model.NormalizedURIStates._
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import play.api.libs.json.{JsNumber, JsObject}
import scala.concurrent.ExecutionContext.Implicits.global

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.{Inject, Singleton}

@Singleton
class AdminArticleIndexerController @Inject()(
    actionAuthenticator: ActionAuthenticator,
    searchClient: SearchServiceClient,
    db: Database,
    normUriRepo: NormalizedURIRepo
  ) extends AdminController(actionAuthenticator) {

  def index = AdminHtmlAction { implicit request =>
    searchClient.index()
    Ok("indexed articles")
  }

  def reindex = AdminHtmlAction { implicit request =>
    searchClient.reindex
    Ok("reindexing started")
  }

  def indexByState(state: State[NormalizedURI]) = AdminHtmlAction { implicit request =>
    transitionByAdmin(state -> Set(SCRAPED, SCRAPE_FAILED)) { newState =>
      db.readWrite { implicit s =>
        normUriRepo.getByState(state).foreach{ uri => normUriRepo.save(uri.withState(newState)) }
      }
      searchClient.index()
      Ok("indexed articles")
    }
  }

  def indexInfo = AdminHtmlAction { implicit request =>
    Async {
      (searchClient.articleIndexInfo() zip searchClient.uriGraphIndexInfo() zip searchClient.commentIndexInfo()).map{ case ((aiInfo, ugInfo), cmInfo) =>
        Ok(views.html.admin.indexer(aiInfo, ugInfo, cmInfo))
      }
    }
  }

  def getSequenceNumber = AdminJsonAction { implicit request =>
    Async {
      searchClient.articleIndexerSequenceNumber().map { number =>
        Ok(JsObject(Seq("sequenceNumber" -> JsNumber(number))))
      }
    }
  }

  def refreshSearcher = AdminHtmlAction { implicit request =>
    searchClient.refreshSearcher()
    Ok("searcher refreshed")
  }

  def dumpLuceneDocument(id: Id[NormalizedURI]) =  AdminHtmlAction { implicit request =>
    Async {
      searchClient.dumpLuceneDocument(id).map(Ok(_))
    }
  }
}

