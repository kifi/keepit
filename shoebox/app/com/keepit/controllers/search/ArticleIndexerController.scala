package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.ArticleIndexerPlugin
import com.keepit.search.index.Indexer.CommitData
import org.apache.lucene.document.Document
import play.api.libs.json._
import play.api.mvc.Action
import views.html

case class ArticleIndexInfo(
    sequenceNumber: Option[SequenceNumber],
    numDocs: Int,
    committedAt: Option[String])

object ArticleIndexInfoJson {
  implicit val articleIndexInfoFormat = Json.format[ArticleIndexInfo]
}

class ArticleIndexerController @Inject()(
    db: Database,
    indexer: ArticleIndexer,
    indexerPlugin: ArticleIndexerPlugin,
    normUriRepo: NormalizedURIRepo) extends FortyTwoController {

  import ArticleIndexInfoJson._

  def index() = Action { implicit request =>
    val cnt = indexerPlugin.index()
    Ok(JsObject(Seq("articles" -> JsNumber(cnt))))
  }

  def reindex() = Action { implicit request =>
    indexerPlugin.reindex()
    Ok(JsObject(Seq("started" -> JsString("ok"))))
  }

  def indexInfo = Action { implicit request =>
    Ok(Json.toJson(ArticleIndexInfo(
      numDocs = indexer.numDocs,
      sequenceNumber = indexer.commitData.get(CommitData.sequenceNumber).map(v => SequenceNumber(v.toLong)),
      committedAt = indexer.commitData.get(CommitData.committedAt)
    )))
  }

  def getSequenceNumber = Action { implicit request =>
    Ok(JsObject(Seq("sequenceNumber" -> JsNumber(indexer.sequenceNumber.value))))
  }

  def refreshSearcher = Action { implicit request =>
    indexer.refreshSearcher()
    Ok("searcher refreshed")
  }

  def dumpLuceneDocument(id: Id[NormalizedURI]) = Action { implicit request =>
    try {
      val doc = indexer.buildIndexable(id).buildDocument
      Ok(html.admin.luceneDocDump("Article", doc, indexer))
    } catch {
      case e: Throwable => Ok(html.admin.luceneDocDump("No Article", new Document, indexer))
    }
  }
}
