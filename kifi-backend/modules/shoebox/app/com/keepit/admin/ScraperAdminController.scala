package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.scraper.ScraperPlugin
import com.keepit.search.ArticleStore

import views.html

class ScraperAdminController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  scraper: ScraperPlugin,
  scrapeInfoRepo: ScrapeInfoRepo,
  normalizedURIRepo: NormalizedURIRepo,
  articleStore: ArticleStore,
  duplicateDocumentRepo: DuplicateDocumentRepo,
  followRepo: FollowRepo,
  deeplinkRepo: DeepLinkRepo,
  bookmarkRepo: BookmarkRepo,
  httpProxyRepo: HttpProxyRepo)
    extends AdminController(actionAuthenticator) {

  def searchScraper = AdminHtmlAction {implicit request =>
    Ok(html.admin.searchScraper())
  }

  def rescrapeByRegex(urlRegex: String, withinMinutes: Int) = AdminHtmlAction { implicit request =>
    val updateCount = db.readWrite { implicit session =>
      scrapeInfoRepo.setForRescrapeByRegex(urlRegex, withinMinutes)
    }
    Redirect(com.keepit.controllers.admin.routes.ScraperAdminController.searchScraper).flashing(
        "success" -> "%s page(s) matching %s to be rescraped within %s minutes(s). ".format(updateCount, urlRegex, withinMinutes)
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
          (normalizedURIRepo.get(article.id), scrapeInfoRepo.getByUriId(article.id))
        } match {
          case (uri, Some(info)) => Ok(html.admin.article(article, uri, info))
          case (uri, None) => errorMsg(id)
        }
      }
      case None => errorMsg(id)
    }
  }

  def getProxies = AdminHtmlAction { implicit request =>
    val proxies = db.readOnly { implicit session => httpProxyRepo.all() }
    Ok(html.admin.proxies(proxies))
  }

  def saveProxies = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    db.readWrite { implicit session =>
      for (key <- body.keys.filter(_.startsWith("alias_")).map(_.substring(6))) {
        val id = Id[HttpProxy](key.toLong)
        val oldProxy = httpProxyRepo.get(id)
        val newProxy = oldProxy.copy(
          state = if (body.contains("active_" + key)) HttpProxyStates.ACTIVE else HttpProxyStates.INACTIVE,
          alias = body("alias_" + key),
          hostname = body("hostname_" + key),
          port = body("port_"+ key).toInt,
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


}

