package com.keepit.search.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db._
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.search.index.Indexable
import com.keepit.search.index.article.{ ArticleFields, ArticleIndexable, ArticleIndexerPlugin, DeprecatedArticleIndexerPlugin }
import com.keepit.search.index.phrase.PhraseIndexer
import com.keepit.search.index.sharding.Shard
import com.keepit.shoebox.ShoeboxServiceClient
import org.apache.lucene.document.Document
import play.api.libs.json._
import play.api.mvc.Action
import views.html
import scala.concurrent.Await
import scala.concurrent.duration._

class ArticleIndexerController @Inject() (
  phraseIndexer: PhraseIndexer,
  deprecatedIndexerPlugin: DeprecatedArticleIndexerPlugin,
  indexerPlugin: ArticleIndexerPlugin,
  shoeboxClient: ShoeboxServiceClient,
  rover: RoverServiceClient)
    extends SearchServiceController {

  def index() = Action { implicit request =>
    deprecatedIndexerPlugin.update()
    Ok(JsObject(Seq("articles" -> JsString("ok"))))
  }

  def reindex() = Action { implicit request =>
    deprecatedIndexerPlugin.reindex()
    Ok(JsObject(Seq("started" -> JsString("ok"))))
  }

  def getSequenceNumber = Action { implicit request =>
    Ok(JsObject(Seq("sequenceNumber" -> JsNumber(deprecatedIndexerPlugin.sequenceNumber.value))))
  }

  def refreshSearcher = Action { implicit request =>
    deprecatedIndexerPlugin.refreshSearcher()
    Ok("searcher refreshed")
  }

  def refreshPhrases = Action { implicit request =>
    phraseIndexer.update()
    Ok("phrases will be updated")
  }

  def refreshWriter = Action { implicit request =>
    deprecatedIndexerPlugin.refreshWriter()
    Ok("writer refreshed")
  }

  def dumpLuceneDocument(id: Id[NormalizedURI], deprecated: Boolean = false) = Action { implicit request =>
    val getDecoder = Indexable.getFieldDecoder(ArticleFields.decoders) _
    try {
      val uri = IndexableUri(Await.result(shoeboxClient.getNormalizedURI(id), 30 seconds))
      val htmlDoc = if (deprecated) {
        val indexer = deprecatedIndexerPlugin.getIndexerFor(id)
        val doc = indexer.buildIndexable(uri).buildDocument
        html.admin.luceneDocDump("DeprecatedArticle", doc, getDecoder)
      } else {
        val articles = Await.result(rover.getBestArticlesByUris(Set(uri.id.get)), 30 seconds)(uri.id.get)
        val doc = ArticleIndexable(uri, articles, Shard(1, 1)).buildDocument
        html.admin.luceneDocDump("DeprecatedArticle", doc, getDecoder)
      }
      Ok(htmlDoc)
    } catch {
      case e: Throwable => Ok(html.admin.luceneDocDump("No Article", new Document, getDecoder))
    }
  }
}
