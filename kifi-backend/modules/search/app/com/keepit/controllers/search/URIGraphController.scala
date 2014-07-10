package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.model.{ Collection, NormalizedURI, User }
import com.keepit.search.graph.URIGraphPlugin
import org.apache.lucene.document.Document
import play.api.libs.json._
import play.api.mvc.Action
import views.html
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.graph.collection.CollectionGraphPlugin
import com.keepit.search.graph.collection.CollectionIndexer
import com.keepit.search.sharding._

class URIGraphController @Inject() (
    activeShards: ActiveShards,
    uriGraphPlugin: URIGraphPlugin,
    collectionGraphPlugin: CollectionGraphPlugin,
    shoeboxClient: ShoeboxServiceClient) extends SearchServiceController {

  def reindex() = Action { implicit request =>
    uriGraphPlugin.reindex()
    collectionGraphPlugin.reindex()
    Ok(JsObject(Seq("started" -> JsString("ok"))))
  }

  def updateURIGraph() = Action { implicit request =>
    uriGraphPlugin.update()
    collectionGraphPlugin.update()
    Ok(JsObject(Seq("started" -> JsString("ok"))))
  }

  def dumpLuceneDocument(id: Id[User]) = Action { implicit request =>
    val uriGraphIndexer = uriGraphPlugin.getIndexerFor(Id[NormalizedURI](0L))
    try {
      val doc = uriGraphIndexer.buildIndexable(id, SequenceNumber.ZERO).buildDocument
      Ok(html.admin.luceneDocDump("URIGraph", doc, uriGraphIndexer))
    } catch {
      case e: Throwable => Ok(html.admin.luceneDocDump("No URIGraph", new Document, uriGraphIndexer))
    }
  }

  def dumpCollectionLuceneDocument(id: Id[Collection], userId: Id[User]) = Action { implicit request =>
    val collectionIndexer = collectionGraphPlugin.getIndexerFor(Id[NormalizedURI](0L))
    try {
      val (collection, bookmarks) = CollectionIndexer.fetchData(id, userId, shoeboxClient)
      val doc = collectionIndexer.buildIndexable(collection, bookmarks).buildDocument
      Ok(html.admin.luceneDocDump("Collection", doc, collectionIndexer))
    } catch {
      case e: Throwable => Ok(html.admin.luceneDocDump("No URIGraph", new Document, collectionIndexer))
    }
  }
}
