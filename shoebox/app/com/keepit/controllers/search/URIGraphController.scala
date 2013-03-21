package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.model.{BookmarkRepo, User}
import com.keepit.search.graph.{URIGraphImpl, URIGraph, URIGraphPlugin}
import org.apache.lucene.document.Document
import play.api.libs.json.{JsObject, JsNumber}
import play.api.mvc.Action
import scala.concurrent.ExecutionContext.Implicits.global
import views.html

class URIGraphController @Inject()(
    db: Database,
    uriGraphPlugin: URIGraphPlugin,
    bookmarkRepo: BookmarkRepo,
    uriGraph: URIGraph) extends FortyTwoController {

  def updateURIGraph() = Action { implicit request =>
    Async {
      uriGraphPlugin.update().map { cnt =>
        Ok(JsObject(Seq("users" -> JsNumber(cnt))))
      }
    }
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
