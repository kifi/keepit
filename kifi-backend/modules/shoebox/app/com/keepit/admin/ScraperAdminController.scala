package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.search.ArticleStore
import views.html
import com.keepit.common.db.slick.Database.Replica
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.scraper.ScrapeSchedulerPlugin
import play.api.mvc.Action
import com.keepit.scraper.ScraperServiceClient
import scala.concurrent.Future
import play.api.libs.json.Json

class ScraperAdminController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  uriRepo: NormalizedURIRepo,
  scrapeInfoRepo: ScrapeInfoRepo,
  scrapeScheduler: ScrapeSchedulerPlugin,
  normalizedURIRepo: NormalizedURIRepo,
  articleStore: ArticleStore,
  httpProxyRepo: HttpProxyRepo,
  scraperServiceClient: ScraperServiceClient)
    extends AdminController(actionAuthenticator) {

  val MAX_COUNT_DISPLAY = 25

  def searchScraper = AdminHtmlAction.authenticated { implicit request => Ok(html.admin.searchScraper()) }

  def scraperRequests(stateFilter: Option[String] = None) = AdminHtmlAction.authenticatedAsync { implicit request =>
    val resultsFuture = db.readOnlyReplicaAsync { implicit ro =>
      (
        scrapeInfoRepo.getAssignedList(MAX_COUNT_DISPLAY),
        scrapeInfoRepo.getOverdueList(MAX_COUNT_DISPLAY),
        scrapeInfoRepo.getAssignedCount(),
        scrapeInfoRepo.getOverdueCount()
      )
    }
    val threadDetailsFuture = Future.sequence(scraperServiceClient.getThreadDetails(stateFilter))
    for {
      (assigned, overdue, assignedCount, overdueCount) <- resultsFuture
      threadDetails <- threadDetailsFuture
    } yield Ok(html.admin.scraperRequests(assigned, overdue, assignedCount, overdueCount, threadDetails))
  }

  def scrapeArticle(url: String) = AdminHtmlAction.authenticatedAsync { implicit request =>
    scrapeScheduler.scrapeBasicArticle(url, None) map { articleOpt =>
      articleOpt match {
        case None => Ok(s"Failed to scrape $url")
        case Some(article) => Ok(s"For $url, article:${article.toString}")
      }
    }
  }

  def rescrapeByRegex() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    val urlRegex = body.getOrElse("urlRegex", "")
    val withinMinutes = body.getOrElse("withinMinutes", "8").toInt
    val updateCount = db.readWrite { implicit session =>
      scrapeInfoRepo.setForRescrapeByRegex(urlRegex, withinMinutes)
    }
    Redirect(com.keepit.controllers.admin.routes.ScraperAdminController.searchScraper).flashing(
      "success" -> "%s page(s) matching %s to be rescraped within %s minutes(s). ".format(updateCount, urlRegex, withinMinutes)
    )
  }

  def getScraped(id: Id[NormalizedURI]) = AdminHtmlAction.authenticated { implicit request =>
    val articleOption = articleStore.get(id)
    val (uri, scrapeInfoOption) = db.readOnlyReplica { implicit s => (normalizedURIRepo.get(id), scrapeInfoRepo.getByUriId(id)) }
    Ok(html.admin.article(articleOption, uri, scrapeInfoOption))
  }

  def getProxies = AdminHtmlAction.authenticated { implicit request =>
    val proxies = db.readOnlyReplica { implicit session => httpProxyRepo.all() }
    Ok(html.admin.proxies(proxies))
  }

  def saveProxies = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    db.readWrite { implicit session =>
      for (key <- body.keys.filter(_.startsWith("alias_")).map(_.substring(6))) {
        val id = Id[HttpProxy](key.toLong)
        val oldProxy = httpProxyRepo.get(id)
        val newProxy = oldProxy.copy(
          state = if (body.contains("active_" + key)) HttpProxyStates.ACTIVE else HttpProxyStates.INACTIVE,
          alias = body("alias_" + key),
          hostname = body("hostname_" + key),
          port = body("port_" + key).toInt,
          scheme = body("scheme_" + key),
          username = Some(body("username_" + key)).filter(!_.isEmpty),
          password = Some(body("password_" + key)).filter(!_.isEmpty)
        )

        if (newProxy != oldProxy) {
          httpProxyRepo.save(newProxy)
        }
      }
      val newProxy = body("new_alias")
      if (newProxy.nonEmpty) {
        httpProxyRepo.save(HttpProxy(
          state = if (body.contains("new_active")) HttpProxyStates.ACTIVE else HttpProxyStates.INACTIVE,
          alias = body("new_alias"),
          hostname = body("new_hostname"),
          port = body("new_port").toInt,
          scheme = body("new_scheme"),
          username = Some(body("new_username")).filter(!_.isEmpty),
          password = Some(body("new_password")).filter(!_.isEmpty)
        ))
      }
    }
    Redirect(routes.ScraperAdminController.getProxies)
  }

  def getPornDetectorModel = AdminHtmlAction.authenticatedAsync { implicit request =>
    val modelFuture = scraperServiceClient.getPornDetectorModel()
    for (model <- modelFuture) yield Ok(Json.toJson(model))
  }

}

