package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.model.{Collection, CollectionStates, NormalizedURI, User}
import com.keepit.search.graph.{URIGraphImpl, URIGraph, URIGraphPlugin}
import com.keepit.search.index.Indexer.CommitData
import org.apache.lucene.document.Document
import play.api.libs.json._
import play.api.mvc.Action
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import views.html
import scala.concurrent.{Await, future, Future}
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.duration._
import com.keepit.search.index.Indexer
import com.keepit.search.{IndexInfo, SharingUserInfo, MainSearcherFactory}
import com.keepit.search.graph.collection.CollectionGraphPlugin
import com.keepit.search.graph.collection.CollectionGraph
import com.keepit.search.graph.collection.CollectionGraphImpl

class URIGraphController @Inject()(
    uriGraphPlugin: URIGraphPlugin,
    collectionGraphPlugin: CollectionGraphPlugin,
    shoeboxClient: ShoeboxServiceClient,
    mainSearcherFactory: MainSearcherFactory,
    uriGraph: URIGraph,
    collectionGraph: CollectionGraph) extends SearchServiceController {

  def reindex() = Action { implicit request =>
    uriGraphPlugin.reindex()
    collectionGraphPlugin.reindex()
    Ok(JsObject(Seq("started" -> JsString("ok"))))
  }

  def reindexCollection() = Action { implicit request =>
    collectionGraphPlugin.reindex()
    Ok(JsObject(Seq("started" -> JsString("ok"))))
  }

  def updateURIGraph() = Action { implicit request =>
    uriGraphPlugin.update()
    collectionGraphPlugin.update()
    Ok(JsObject(Seq("started" -> JsString("ok"))))
  }

  def sharingUserInfo(userId: Id[User]) = Action(parse.json) { implicit request =>
    val infosFuture = future {
      val searcher = mainSearcherFactory.getURIGraphSearcher(userId)
      val ids = request.body.as[Seq[Long]].map(Id[NormalizedURI](_))
      ids map searcher.getSharingUserInfo
    }
    Async {
      infosFuture.map(info => Ok(Json.toJson(info)))
    }
  }

  def indexInfo = Action { implicit request =>
    val uriGraphIndexer = uriGraph.asInstanceOf[URIGraphImpl].uriGraphIndexer
    val bookmarkStore = uriGraphIndexer.bookmarkStore
    val collectionIndexer = collectionGraph.asInstanceOf[CollectionGraphImpl].collectionIndexer

    Ok(Json.toJson(Seq(
        mkIndexInfo("URIGraphIndex", uriGraphIndexer),
        mkIndexInfo("BookmarkStore", bookmarkStore),
        mkIndexInfo("CollectionIndex", collectionIndexer)
    )))
  }

  private def mkIndexInfo(name: String, indexer: Indexer[_]): IndexInfo = {
    IndexInfo(
      name = name,
      numDocs = indexer.numDocs,
      sequenceNumber = Some(indexer.commitSequenceNumber),
      committedAt = indexer.committedAt
    )
  }

  def dumpLuceneDocument(id: Id[User]) = Action { implicit request =>
    val uriGraphIndexer = uriGraph.asInstanceOf[URIGraphImpl].uriGraphIndexer
    try {
      val doc = uriGraphIndexer.buildIndexable(id, SequenceNumber.ZERO).buildDocument
      Ok(html.admin.luceneDocDump("URIGraph", doc, uriGraphIndexer))
    } catch {
      case e: Throwable => Ok(html.admin.luceneDocDump("No URIGraph", new Document, uriGraphIndexer))
    }
  }

  def dumpCollectionLuceneDocument(id: Id[Collection], userId: Id[User]) = Action { implicit request =>
    val collectionIndexer = collectionGraph.asInstanceOf[CollectionGraphImpl].collectionIndexer
    try {
      val collection = Await.result(shoeboxClient.getCollectionsByUser(userId), 180 seconds).find(_.id.get == id).get
      val doc = collectionIndexer.buildIndexable(collection).buildDocument
      Ok(html.admin.luceneDocDump("Collection", doc, collectionIndexer))
    } catch {
      case e: Throwable => Ok(html.admin.luceneDocDump("No URIGraph", new Document, collectionIndexer))
    }
  }
}
