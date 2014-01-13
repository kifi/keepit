package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.model.{Collection, CollectionStates, NormalizedURI, User}
import com.keepit.search.graph.{URIGraphPlugin}
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
import com.keepit.search.graph.bookmark.URIGraphIndexer
import com.keepit.search.graph.collection.CollectionIndexer
import com.keepit.search.sharding._

class URIGraphController @Inject()(
    activeShards: ActiveShards,
    uriGraphPlugin: URIGraphPlugin,
    collectionGraphPlugin: CollectionGraphPlugin,
    shoeboxClient: ShoeboxServiceClient,
    mainSearcherFactory: MainSearcherFactory,
    shardedUriGraphIndexer: ShardedURIGraphIndexer,
    shardedCollectionIndexer: ShardedCollectionIndexer) extends SearchServiceController {

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

  def sharingUserInfo(userId: Id[User]) = Action(parse.json) { implicit request =>
    val infosFuture = future {
      val ids = request.body.as[Seq[Long]].map(Id[NormalizedURI](_))
      ids.map{ id =>
        activeShards.find(id) match {
          case Some(shard) =>
            val searcher = mainSearcherFactory.getURIGraphSearcher(shard, userId)
            searcher.getSharingUserInfo(id)
          case None =>
            throw new Exception("shard not found")
        }
      }
    }
    Async {
      infosFuture.map(info => Ok(Json.toJson(info)))
    }
  }

  def indexInfo = Action { implicit request =>

    Ok(Json.toJson(
        activeShards.shards.map{ shard =>
          val uriGraphIndexer = shardedUriGraphIndexer.getIndexer(shard)
          val bookmarkStore = uriGraphIndexer.bookmarkStore
          val collectionIndexer = shardedCollectionIndexer.getIndexer(shard)
          Seq(
            mkIndexInfo("URIGraphIndex", uriGraphIndexer),
            mkIndexInfo("BookmarkStore", bookmarkStore),
            mkIndexInfo(s"CollectionIndex${shard.indexNameSuffix}", collectionIndexer)
          )
        }
      )
    )
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
    val uriGraphIndexer = shardedUriGraphIndexer.getIndexerFor(1l)
    try {
      val doc = uriGraphIndexer.buildIndexable(id, SequenceNumber.ZERO).buildDocument
      Ok(html.admin.luceneDocDump("URIGraph", doc, uriGraphIndexer))
    } catch {
      case e: Throwable => Ok(html.admin.luceneDocDump("No URIGraph", new Document, uriGraphIndexer))
    }
  }

  def dumpCollectionLuceneDocument(id: Id[Collection], userId: Id[User]) = Action { implicit request =>
    val collectionIndexer = collectionGraphPlugin.getIndexerFor(1L)
    try {
      val collections = Await.result(shoeboxClient.getCollectionsByUser(userId), 180 seconds).find(_.id.get == id).get
      val doc = collectionIndexer.buildIndexable(collections).buildDocument
      Ok(html.admin.luceneDocDump("Collection", doc, collectionIndexer))
    } catch {
      case e: Throwable => Ok(html.admin.luceneDocDump("No URIGraph", new Document, collectionIndexer))
    }
  }
}
