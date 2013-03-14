package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.AdminController
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model.NormalizedURIStates._
import com.keepit.model._
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.ArticleIndexerPlugin
import org.apache.lucene.document.Document
import play.api.libs.json.{JsNumber, JsObject}
import views.html

class ArticleIndexerController @Inject()(
    db: Database,
    indexerPlugin: ArticleIndexerPlugin,
    indexer: ArticleIndexer,
    normUriRepo: NormalizedURIRepo
  ) extends AdminController {

  def index = AdminHtmlAction { implicit request =>
    val cnt = indexerPlugin.index()
    Ok("indexed %d articles".format(cnt))
  }

  def indexByState(state: State[NormalizedURI]) = AdminHtmlAction { implicit request =>
    transitionByAdmin(state -> Set(SCRAPED, SCRAPE_FAILED)) { newState =>
      db.readWrite { implicit s =>
        normUriRepo.getByState(state).foreach{ uri => normUriRepo.save(uri.withState(newState)) }
      }
      val cnt = indexerPlugin.index()
      Ok("indexed %d articles".format(cnt))
    }
  }

  def indexInfo = AdminHtmlAction { implicit request =>
    Ok(html.admin.indexer(indexer))
  }

  def getSequenceNumber = AdminJsonAction { implicit request =>
    Ok(JsObject(Seq("sequenceNumber" -> JsNumber(indexer.sequenceNumber.value))))
  }

  def refreshSearcher = AdminHtmlAction { implicit request =>
    indexer.refreshSearcher()
    Ok("searcher refreshed")
  }

  def dumpLuceneDocument(id: Id[NormalizedURI]) =  AdminHtmlAction { implicit request =>
    try {
      val doc = indexer.buildIndexable(id).buildDocument
      Ok(html.admin.luceneDocDump("Article", doc, indexer))
    } catch {
      case e: Throwable => Ok(html.admin.luceneDocDump("No Article", new Document, indexer))
    }
  }
}

