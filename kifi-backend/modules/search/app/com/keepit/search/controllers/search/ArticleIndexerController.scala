package com.keepit.search.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db._
import com.keepit.model._
import com.keepit.search.index.article.DeprecatedArticleIndexerPlugin
import com.keepit.search.index.phrase.PhraseIndexer
import com.keepit.shoebox.ShoeboxServiceClient
import org.apache.lucene.document.Document
import play.api.libs.json._
import play.api.mvc.Action
import views.html
import scala.concurrent.Await
import scala.concurrent.duration._

class ArticleIndexerController @Inject() (
  phraseIndexer: PhraseIndexer,
  indexerPlugin: DeprecatedArticleIndexerPlugin,
  shoeboxClient: ShoeboxServiceClient)
    extends SearchServiceController {

  def index() = Action { implicit request =>
    indexerPlugin.update()
    Ok(JsObject(Seq("articles" -> JsString("ok"))))
  }

  def reindex() = Action { implicit request =>
    indexerPlugin.reindex()
    Ok(JsObject(Seq("started" -> JsString("ok"))))
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

  def refreshWriter = Action { implicit request =>
    indexerPlugin.refreshWriter()
    Ok("writer refreshed")
  }

  def dumpLuceneDocument(id: Id[NormalizedURI]) = Action { implicit request =>
    val indexer = indexerPlugin.getIndexerFor(id)
    try {
      val uri = Await.result(shoeboxClient.getNormalizedURI(id), 30 seconds)
      val doc = indexer.buildIndexable(IndexableUri(uri)).buildDocument
      Ok(html.admin.luceneDocDump("Article", doc, indexer))
    } catch {
      case e: Throwable => Ok(html.admin.luceneDocDump("No Article", new Document, indexer))
    }
  }
}
