package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.model.{NormalizedURI, SocialConnectionRepo, BookmarkRepo, User}
import com.keepit.search.graph.{URIGraphImpl, URIGraph, URIGraphPlugin}
import com.keepit.search.index.Indexer.CommitData
import org.apache.lucene.document.Document
import play.api.libs.json._
import play.api.mvc.Action
import scala.concurrent.ExecutionContext.Implicits.global
import views.html

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
    socialConnectionRepo: SocialConnectionRepo,
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

  def sharingUserInfo(userId: Id[User], uriId: Id[NormalizedURI]) = Action { implicit request =>
    val friendIds = db.readOnly { implicit s => socialConnectionRepo.getFortyTwoUserConnections(userId) }
    val searcher = uriGraph.getURIGraphSearcher
    val friendEdgeSet = searcher.getUserToUserEdgeSet(userId, friendIds)
    val keepersEdgeSet = searcher.getUriToUserEdgeSet(uriId)
    val sharingUserIds = searcher.intersect(friendEdgeSet, keepersEdgeSet).destIdSet - userId
    Ok(Json.toJson(SharingUserInfo(sharingUserIds, keepersEdgeSet.size)))
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
