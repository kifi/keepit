package com.keepit.controllers.admin

import play.api.data._
import java.util.concurrent.TimeUnit
import play.api._
import libs.concurrent.Akka
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
import com.keepit.scraper._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.search.ArticleStore
import com.keepit.common.controller.FortyTwoController
import javax.xml.bind.DatatypeConverter._
import com.keepit.common.mail._
import slick.DBConnection
import com.keepit.scraper.DuplicateDocumentDetection

object ScraperController extends FortyTwoController {

  def scrape = AdminHtmlAction { implicit request =>
    val scraper = inject[ScraperPlugin]
    val articles = scraper.scrape()
    Ok(views.html.scrape(articles))
  }

  def duplicateDocumentDetection = AdminHtmlAction { implicit request =>
    val dupeDetect = new DuplicateDocumentDetection
    dupeDetect.asyncProcessDocuments()

    Ok("Dupe logging started. Expect an email :)")
  }

  def scrapeByState(state: State[NormalizedURI]) = AdminHtmlAction { implicit request =>
    transitionByAdmin(state -> Set(ACTIVE)) { newState =>
      CX.withConnection { implicit c =>
        NormalizedURICxRepo.getByState(state).foreach{ uri => uri.withState(newState).save }
      }
      val scraper = inject[ScraperPlugin]
      val articles = scraper.scrape()
      Ok(views.html.scrape(articles))
    }
  }

  def getScraped(id: Id[NormalizedURI]) = AdminHtmlAction { implicit request =>
    val store = inject[ArticleStore]
    val article = store.get(id).get
    val uri = CX.withConnection { implicit c =>
      NormalizedURICxRepo.get(article.id)
    }
    Ok(views.html.article(article, uri))
  }

  def getUnscrapable() = AdminHtmlAction { implicit request =>
    val docs = inject[DBConnection].readOnly { implicit conn =>
      inject[UnscrapableRepo].allActive()
    }

    Ok(views.html.unscrapable(docs))
  }

  def createUnscrapable() = AdminHtmlAction { implicit request =>
    val form = request.request.body.asFormUrlEncoded match {
      case Some(req) => req.map(r => (r._1 -> r._2.head))
      case None => throw new Exception("No form data given.")
    }
    val pattern = form.get("pattern").get
    inject[DBConnection].readWrite { implicit conn =>
      inject[UnscrapableRepo].save(Unscrapable(pattern = pattern))
    }
    Redirect(com.keepit.controllers.admin.routes.ScraperController.getUnscrapable())
  }
}

