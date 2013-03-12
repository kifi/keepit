package com.keepit.controllers.admin

import scala.concurrent.ExecutionContext.Implicits.global

import org.apache.lucene.document.Document

import com.keepit.common.controller.AdminController
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.inject._
import com.keepit.model.{BookmarkRepo, User}
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphImpl
import com.keepit.search.graph.URIGraphPlugin

import play.api.Play.current
import views.html

object URIGraphController extends AdminController {

  def load = AdminHtmlAction { implicit request =>
    val uriGraphPlugin = inject[URIGraphPlugin]
    Async {
      uriGraphPlugin.update().map { cnt =>
        Ok(s"indexed $cnt users")
      }
    }
  }

  def update(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val uriGraphPlugin = inject[URIGraphPlugin]
    val bookmarkRepo = inject[BookmarkRepo]
    val db = inject[Database]
    Async {
      val bookmarks = db.readOnly { implicit s => bookmarkRepo.getByUser(userId) }
      bookmarks.grouped(1000).foreach { group =>
        db.readWrite { implicit s => group.foreach(bookmarkRepo.save) }
      }
      uriGraphPlugin.update().map { cnt =>
        Ok(s"indexed $cnt users")
      }
    }
  }

  def dumpLuceneDocument(id: Id[User]) =  AdminHtmlAction { implicit request =>
    val indexer = inject[URIGraph].asInstanceOf[URIGraphImpl]
    try {
      val doc = indexer.buildIndexable(id, SequenceNumber.ZERO).buildDocument
      Ok(html.admin.luceneDocDump("URIGraph", doc, indexer))
    } catch {
      case e: Throwable => Ok(html.admin.luceneDocDump("No URIGraph", new Document, indexer))
    }
  }
}

