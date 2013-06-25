package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.model.Comment
import com.keepit.search.comment.{CommentIndexer, CommentIndexerPlugin}
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


class CommentIndexerController @Inject()(
    commentIndexerPlugin: CommentIndexerPlugin,
    shoeboxClient: ShoeboxServiceClient,
    commentIndexer: CommentIndexer) extends SearchServiceController {

  def reindex() = Action { implicit request =>
    commentIndexerPlugin.reindex()
    Ok(JsObject(Seq("started" -> JsString("ok"))))
  }

  def update() = Action { implicit request =>
    Async {
      commentIndexerPlugin.update().map { cnt =>
        Ok(JsObject(Seq("comments" -> JsNumber(cnt))))
      }
    }
  }

  def indexInfo() = Action { implicit request =>
    Ok(Json.toJson(Seq(
        mkIndexInfo("CommentIndex", commentIndexer),
        mkIndexInfo("CommentStore", commentIndexer.commentStore))
    ))
  }

  private def mkIndexInfo(name: String, indexer: Indexer[_]): IndexInfo = {
    IndexInfo(
      name = name,
      numDocs = indexer.numDocs,
      sequenceNumber = indexer.commitData.get(CommitData.sequenceNumber).map(v => SequenceNumber(v.toLong)),
      committedAt = indexer.commitData.get(CommitData.committedAt)
    )
  }

  def dumpLuceneDocument(id: Id[Comment]) = Action { implicit request =>
    try {
      val doc = commentIndexer.buildIndexable((id, SequenceNumber.ZERO)).buildDocument
      Ok(html.admin.luceneDocDump("Comment", doc, commentIndexer))
    } catch {
      case e: Throwable => Ok(html.admin.luceneDocDump("No Comment", new Document, commentIndexer))
    }
  }
}
