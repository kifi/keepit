package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.model.BookmarkRepo
import com.keepit.model.User
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

object URIGraphIndexInfoJson {
  implicit val format = Json.format[URIGraphIndexInfo]
}

class URIGraphController @Inject()(
    db: Database,
    uriGraphPlugin: URIGraphPlugin,
    bookmarkRepo: BookmarkRepo,
    uriGraph: URIGraph) extends FortyTwoController {

  import URIGraphIndexInfoJson._

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
