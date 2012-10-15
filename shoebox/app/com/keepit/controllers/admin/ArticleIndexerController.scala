package com.keepit.controllers.admin

import play.api.data._
import java.util.concurrent.TimeUnit
import play.api._
import play.api.Play.current
import play.api.mvc._
import com.keepit.common.logging.Logging
import com.keepit.controllers.CommonActions._
import com.keepit.inject._
import com.keepit.search.index.ArticleIndexerPlugin
import com.keepit.search.index.ArticleIndexer

object ArticleIndexerController extends Controller with Logging {

  def index = Action { implicit request => 
    val indexer = inject[ArticleIndexerPlugin]
    val cnt = indexer.index()
    Ok("indexed %d articles".format(cnt))
  }

  def indexInfo = Action { implicit request => 
    val indexer = inject[ArticleIndexer]
    Ok(views.html.indexer(indexer))
  }
  
  def refreshSearcher = Action { implicit request => 
    val indexer = inject[ArticleIndexer]
    indexer.refreshSearcher
    Ok("searcher refreshed")
  }
}

