package com.keepit.controllers.admin

import scala.Option.option2Iterable
import scala.concurrent.ExecutionContext.Implicits.global

import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.BookmarkRepo
import com.keepit.model.BookmarkRepoImpl
import com.keepit.model.DeepLinkRepo
import com.keepit.model.DuplicateDocumentRepo
import com.keepit.model.FollowRepo
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURIRepo
import com.keepit.model.NormalizedURIRepoImpl
import com.keepit.model.ScrapeInfoRepo
import com.keepit.model.UrlPatternRule
import com.keepit.model.UrlPatternRuleRepo
import com.keepit.scraper.ScraperPlugin
import com.keepit.search.ArticleStore

import views.html

class ScraperController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  scraper: ScraperPlugin,
  scrapeInfoRepo: ScrapeInfoRepo,
  normalizedURIRepo: NormalizedURIRepo,
  articleStore: ArticleStore,
  urlPatternRuleRepo: UrlPatternRuleRepo,
  duplicateDocumentRepo: DuplicateDocumentRepo,
  followRepo: FollowRepo,
  deeplinkRepo: DeepLinkRepo,
  bookmarkRepo: BookmarkRepo)
    extends AdminController(actionAuthenticator) {

  def scrape = AdminHtmlAction { implicit request =>
    Async {
      scraper.scrapePending() map { articles =>
        Ok(html.admin.scrape(articles))
      }
    }
  }

  def searchScraper = AdminHtmlAction {implicit request =>
    Ok(html.admin.searchScraper())
  }

  def rescrapeByRegex(urlRegex: String, withinHours: Int) = AdminHtmlAction { implicit request =>
    val updateCount = db.readWrite { implicit session =>
      scrapeInfoRepo.setForRescrapeByRegex(urlRegex, withinHours)
    }
    Redirect(com.keepit.controllers.admin.routes.ScraperController.searchScraper).flashing(
        "success" -> "%s page(s) matching %s to be rescraped within %s hour(s). ".format(updateCount,urlRegex,withinHours)
      )
  }

  def getScraped(id: Id[NormalizedURI]) = AdminHtmlAction { implicit request =>
    def errorMsg(id: Id[NormalizedURI]) = {
      val uri = db.readOnly{ implicit s =>
        normalizedURIRepo.get(id)
      }
      Ok(s"Oops, this page was not scraped.\ntitle = ${uri.title.getOrElse("N/A")}\nurl = ${uri.url}")
    }

    articleStore.get(id) match {
      case Some(article) => {
        db.readOnly { implicit s =>
          (normalizedURIRepo.get(article.id), scrapeInfoRepo.getByUri(article.id))
        } match {
          case (uri, Some(info)) => Ok(html.admin.article(article, uri, info))
          case (uri, None) => errorMsg(id)
        }
      }
      case None => errorMsg(id)
    }
  }

  def getUnscrapable() = AdminHtmlAction { implicit request =>
    val docs = db.readOnly { implicit conn =>
      urlPatternRuleRepo.allActive().filter(_.isUnscrapable)
    }

    Ok(html.admin.unscrapable(docs))
  }

  def previewUnscrapable() = AdminHtmlAction { implicit request =>
    val form = request.request.body.asFormUrlEncoded match {
      case Some(req) => req.map(r => (r._1 -> r._2.head))
      case None => throw new Exception("No form data given.")
    }
    val pattern = form.get("pattern").get
    val numRecords = form.get("count").get.toInt
    val records = {
      val (destinSet, normSet) = db.readWrite { implicit conn =>
        val paged = scrapeInfoRepo.page(0, numRecords)
        (paged.map(si => si.destinationUrl).flatten, paged.map(si => normalizedURIRepo.get(si.uriId).url))
      }
      destinSet.filter(_.matches(pattern)) ++ normSet.filter(_.matches(pattern))
    }
    Ok(html.admin.unscrapablePreview(pattern, numRecords, records))
  }

  def createUnscrapable() = AdminHtmlAction { implicit request =>
    val form = request.request.body.asFormUrlEncoded match {
      case Some(req) => req.map(r => (r._1 -> r._2.head))
      case None => throw new Exception("No form data given.")
    }
    val pattern = form.get("pattern").get
    db.readWrite { implicit conn =>
      urlPatternRuleRepo.save(UrlPatternRule(pattern = pattern, isUnscrapable = true))
    }
    Redirect(com.keepit.controllers.admin.routes.ScraperController.getUnscrapable())
  }
}

