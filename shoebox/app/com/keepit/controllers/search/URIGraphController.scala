package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.model.{NormalizedURI, BookmarkRepo, User}
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

case class URIGraphIndexInfo(
    sequenceNumber: Option[SequenceNumber],
    numDocs: Int,
    committedAt: Option[String])

case class SharingUserInfo(
    sharingUserIds: Set[Id[User]],
    keepersEdgeSetSize: Int)

object URIGraphJson {
  implicit val uriGraphIndexInfoFormat = Json.format[URIGraphIndexInfo]

  private implicit val userIdFormat = Id.format[User]
  implicit val sharingUserInfoFormat = Json.format[SharingUserInfo]
}


class URIGraphController @Inject()(
    db: Database,
    uriGraphPlugin: URIGraphPlugin,
    bookmarkRepo: BookmarkRepo,
    shoeboxClient: ShoeboxServiceClient,
    uriGraph: URIGraph) extends SearchServiceController {

  import URIGraphJson._

  def reindex() = Action { implicit request =>
    uriGraphPlugin.reindex()
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
    val friendIdsFuture = shoeboxClient.getConnectedUsers(userId.id)
    val friendIds = Await.result(friendIdsFuture, 5 seconds)
    val searcher = uriGraph.getURIGraphSearcher(None)
    val friendEdgeSet = searcher.getUserToUserEdgeSet(userId, friendIds)
    uriIds.map { uriId =>
      val keepersEdgeSet = searcher.getUriToUserEdgeSet(uriId)
      val sharingUserIds = searcher.intersect(friendEdgeSet, keepersEdgeSet).destIdSet - userId
      SharingUserInfo(sharingUserIds, keepersEdgeSet.size)
    }
  }

  def sharingUserInfo(userId: Id[User], uriIds: String) = Action { implicit request =>
    val ids = uriIds.split(",").map(_.trim).collect { case idStr if !idStr.isEmpty => Id[NormalizedURI](idStr.toLong) }
    Ok(Json.toJson(getSharingUserInfo(userId, ids)))
  }

  def indexInfo = Action { implicit request =>
    val uriGraphImpl = uriGraph.asInstanceOf[URIGraphImpl]
    Ok(Json.toJson(URIGraphIndexInfo(
      numDocs = uriGraphImpl.numDocs,
      sequenceNumber = uriGraphImpl.commitData.get(CommitData.sequenceNumber).map(v => SequenceNumber(v.toLong)),
      committedAt = uriGraphImpl.commitData.get(CommitData.committedAt)
    )))
  }

  def dumpLuceneDocument(id: Id[User]) = Action { implicit request =>
    val indexer = uriGraph.asInstanceOf[URIGraphImpl]
    try {
      val doc = indexer.buildIndexable(id, SequenceNumber.ZERO).buildDocument
      Ok(html.admin.luceneDocDump("URIGraph", doc, indexer))
    } catch {
      case e: Throwable => Ok(html.admin.luceneDocDump("No URIGraph", new Document, indexer))
    }
  }
}
