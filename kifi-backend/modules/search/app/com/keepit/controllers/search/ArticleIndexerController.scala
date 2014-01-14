package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db._
import com.keepit.model._
import com.keepit.search.article.ArticleIndexer
import com.keepit.search.article.ArticleIndexerPlugin
import com.keepit.search.index.Indexer.CommitData
import com.keepit.search.phrasedetector.PhraseIndexer
import org.apache.lucene.document.Document
import play.api.libs.json._
import play.api.mvc.Action
import views.html
import com.keepit.search.IndexInfo

class ArticleIndexerController @Inject()(
    phraseIndexer: PhraseIndexer,
    indexerPlugin: ArticleIndexerPlugin)
  extends SearchServiceController {

  def index() = Action { implicit request =>
    val cnt = indexerPlugin.update()
    Ok(JsObject(Seq("articles" -> JsNumber(cnt))))
  }

  def reindex() = Action { implicit request =>
    indexerPlugin.reindex()
    Ok(JsObject(Seq("started" -> JsString("ok"))))
  }

  def indexInfo = Action { implicit request =>
    Ok(Json.toJson(IndexInfo(
      name = "ArticleIndex",
      numDocs = indexerPlugin.numDocs,
      sequenceNumber = Some(indexerPlugin.commitSequenceNumber),
      committedAt = indexerPlugin.committedAt
    )))
  }

  def getSequenceNumber = Action { implicit request =>
    Ok(JsObject(Seq("sequenceNumber" -> JsNumber(indexerPlugin.sequenceNumber.value))))
  }

  def refreshSearcher = Action { implicit request =>
    indexerPlugin.refreshSearcher()
    Ok("searcher refreshed")
  }

  def refreshPhrases = Action { implicit request =>
    phraseIndexer.update()
    Ok("phrases will be updated")
  }

  def dumpLuceneDocument(id: Id[NormalizedURI]) = Action { implicit request =>
    val indexer = indexerPlugin.getIndexerFor(id)
    try {
      val doc = indexer.buildIndexable(id).buildDocument
      Ok(html.admin.luceneDocDump("Article", doc, indexer))
    } catch {
      case e: Throwable => Ok(html.admin.luceneDocDump("No Article", new Document, indexer))
    }
  }
}
