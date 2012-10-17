package com.keepit.controllers.admin

import play.api.data._
import java.util.concurrent.TimeUnit
import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsNumber
import com.keepit.common.db._
import com.keepit.common.logging.Logging
import com.keepit.controllers.CommonActions._
import com.keepit.inject._
import com.keepit.scraper.ScraperPlugin
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURI.States._
import com.keepit.search.ArticleStore

object ScraperController extends Controller with Logging {

  def scrape = Action { implicit request => 
    val scraper = inject[ScraperPlugin]
    val articles = scraper.scrape()
    Ok(views.html.scrape(articles))
  }
  
  def scrapeByState(state: State[NormalizedURI]) = Action { implicit request =>
    def updateStateFrom(oldState: State[NormalizedURI]) = CX.withConnection { implicit c =>
      NormalizedURI.getByState(oldState).foreach{ uri => uri.withState(ACTIVE).save }
    }
    state match { // TODO: factor out valid state transitions
      case SCRAPED => updateStateFrom(state)
      case SCRAPE_FAILED => updateStateFrom(state)
      case INDEXED => updateStateFrom(state)
      case INDEX_FAILED => updateStateFrom(state)
      case _ => // ignore
    }
    val scraper = inject[ScraperPlugin]
    val articles = scraper.scrape()
    Ok(views.html.scrape(articles))
  }

  def getScraped(id: Id[NormalizedURI]) = Action { implicit request => 
    val store = inject[ArticleStore]
    val article = store.get(id).get
    val uri = CX.withConnection { implicit c =>
      NormalizedURI.get(article.normalizedUriId)
    }
    Ok(views.html.article(article, uri))
  }
}

