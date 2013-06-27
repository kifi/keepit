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
import scala.concurrent.ExecutionContext.Implicits.global
import views.html
import scala.concurrent.Await
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.duration._
import com.keepit.search.index.Indexer
import com.keepit.common.search.SharingUserInfo
import com.keepit.common.search.IndexInfo


class URIGraphController @Inject()(
    uriGraphPlugin: URIGraphPlugin,
    shoeboxClient: ShoeboxServiceClient,
    uriGraph: URIGraph) extends SearchServiceController {

  def reindex() = Action { implicit request =>
    uriGraphPlugin.reindex()
    Ok(JsObject(Seq("started" -> JsString("ok"))))
  }

  def reindexCollection() = Action { implicit request =>
    uriGraphPlugin.reindexCollection()
    Ok(JsObject(Seq("started" -> JsString("ok"))))
  }

  def updateURIGraph() = Action { implicit request =>
    Async {
      uriGraphPlugin.update().map { cnt =>
        Ok(JsObject(Seq("users" -> JsNumber(cnt))))
      }
    }
  }

  private def getSharingUserInfo(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Seq[SharingUserInfo] = {
    val friendIdsFuture = shoeboxClient.getConnectedUsers(userId)
    val searcher = uriGraph.getURIGraphSearcher()
    lazy val friendEdgeSet = {
      val friendIds = Await.result(friendIdsFuture, 5 seconds)
      searcher.getUserToUserEdgeSet(userId, friendIds - userId)
    }
    uriIds.map { uriId =>
      val keepersEdgeSet = searcher.getUriToUserEdgeSet(uriId)
      val sharingUserIds = searcher.intersect(friendEdgeSet, keepersEdgeSet).destIdSet
      SharingUserInfo(sharingUserIds, keepersEdgeSet.size)
    }
  }

  def sharingUserInfo(userId: Id[User], uriIds: String) = Action { implicit request =>
    val ids = uriIds.split(",").map(_.trim).collect { case idStr if !idStr.isEmpty => Id[NormalizedURI](idStr.toLong) }
    Ok(Json.toJson(getSharingUserInfo(userId, ids)))
  }

  def indexInfo = Action { implicit request =>
    val uriGraphIndexer = uriGraph.asInstanceOf[URIGraphImpl].uriGraphIndexer
    val bookmarkStore = uriGraphIndexer.bookmarkStore
    val collectionIndexer = uriGraph.asInstanceOf[URIGraphImpl].collectionIndexer

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
      sequenceNumber = indexer.commitData.get(CommitData.sequenceNumber).map(v => SequenceNumber(v.toLong)),
      committedAt = indexer.commitData.get(CommitData.committedAt)
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
    val collectionIndexer = uriGraph.asInstanceOf[URIGraphImpl].collectionIndexer
    try {
      val doc = collectionIndexer.buildIndexable(id, userId, SequenceNumber.ZERO, CollectionStates.ACTIVE).buildDocument
      Ok(html.admin.luceneDocDump("Collection", doc, collectionIndexer))
    } catch {
      case e: Throwable => Ok(html.admin.luceneDocDump("No URIGraph", new Document, collectionIndexer))
    }
  }
}
