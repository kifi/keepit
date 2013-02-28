package com.keepit.controllers.admin

import play.api.data._
import java.util.concurrent.TimeUnit
import play.api._
import play.api.Play.current
import play.api.mvc._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.logging.Logging

import com.keepit.inject._
import com.keepit.search.index.ArticleIndexerPlugin
import com.keepit.search.index.ArticleIndexer
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.common.controller.FortyTwoController
import org.apache.lucene.document.Document

object ArticleIndexerController extends FortyTwoController {

  def index = AdminHtmlAction { implicit request =>
    val indexer = inject[ArticleIndexerPlugin]
    val cnt = indexer.index()
    Ok("indexed %d articles".format(cnt))
  }

  def indexByState(state: State[NormalizedURI]) = AdminHtmlAction { implicit request =>
    transitionByAdmin(state -> Set(SCRAPED, SCRAPE_FAILED)) { newState =>
      inject[Database].readWrite { implicit s =>
        val repo = inject[NormalizedURIRepo]
        repo.getByState(state).foreach{ uri => repo.save(uri.withState(newState)) }
      }
      val indexer = inject[ArticleIndexerPlugin]
      val cnt = indexer.index()
      Ok("indexed %d articles".format(cnt))
    }
  }

  def indexInfo = AdminHtmlAction { implicit request =>
    val indexer = inject[ArticleIndexer]
    Ok(views.html.indexer(indexer))
  }

  def refreshSearcher = AdminHtmlAction { implicit request =>
    val indexer = inject[ArticleIndexer]
    indexer.refreshSearcher
    Ok("searcher refreshed")
  }

  def dumpLuceneDocument(id: Id[NormalizedURI]) =  AdminHtmlAction { implicit request =>
    val indexer = inject[ArticleIndexer]
    try {
      val doc = indexer.buildIndexable(id).buildDocument
      Ok(views.html.luceneDocDump("Article", doc, indexer))
    } catch {
      case e: Throwable => Ok(views.html.luceneDocDump("No Article", new Document, indexer))
    }
  }
}

