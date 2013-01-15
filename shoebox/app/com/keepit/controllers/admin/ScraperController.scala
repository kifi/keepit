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

object ScraperController extends FortyTwoController {

  def scrape = AdminHtmlAction { implicit request =>
    val scraper = inject[ScraperPlugin]
    val articles = scraper.scrape()
    Ok(views.html.scrape(articles))
  }

  def duplicateDocumentDetection = AdminHtmlAction { implicit request =>

    Akka.future {
      val startTime = System.currentTimeMillis
      val documentSignatures = CX.withConnection { implicit conn =>
        ScrapeInfoCxRepo.all.map(s => (s.uriId, parseBase64Binary(s.signature)))
      }
      val dupe = new DuplicateDocumentDetection(documentSignatures)
      val docs = dupe.processDocuments()

      val result = "Runtime: %sms".format(System.currentTimeMillis - startTime) + CX.withConnection { implicit conn =>
        docs.collect { case (id,similars) =>
          val t = NormalizedURICxRepo.get(id)
          t.id.get.id + "\t" + t.url.take(150) + "\n<br>" +
            similars.map { sid =>
              val s = NormalizedURICxRepo.get(sid._1)
              "\t" + sid._2 + "\t" + s.id.get.id + "\t" + s.url.take(150)
            }.mkString("\n<br>")
        }.mkString("\n<br>")
      }
      val postOffice = inject[PostOffice]
      postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = "Duplication Report", htmlBody = result, category = PostOffice.Categories.ADMIN))
    }
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
}

