package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.search.ArticleStore
import views.html
import com.keepit.common.db.slick.Database.Replica
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.scraper.ScrapeScheduler
import play.api.mvc.Action
import com.keepit.scraper.ScraperServiceClient
import scala.concurrent.Future
import play.api.libs.json.Json
import com.keepit.common.akka.SafeFuture
import play.api.libs.iteratee.{ Concurrent, Enumerator }

class ScraperAdminController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  uriRepo: NormalizedURIRepo,
  scrapeInfoRepo: ScrapeInfoRepo,
  scrapeScheduler: ScrapeScheduler,
  normalizedURIRepo: NormalizedURIRepo,
  articleStore: ArticleStore,
  httpProxyRepo: HttpProxyRepo,
  scraperServiceClient: ScraperServiceClient)
    extends AdminUserActions {

  val MAX_COUNT_DISPLAY = 25

  def status = AdminUserAction.async { implicit request =>
    Future.sequence(scraperServiceClient.status()).map { res =>
      Ok(Json.toJson(res.map { case (_, jobs) => jobs }.flatten))
    }
  }

  def searchScraper = AdminUserPage { implicit request => Ok(html.admin.searchScraper()) }

  def scraperRequests(stateFilter: Option[String] = None) = AdminUserPage.async { implicit request =>
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

  def scrapeArticle(url: String) = AdminUserPage.async { implicit request =>
    scrapeScheduler.scrapeBasicArticle(url, None) map { articleOpt =>
      articleOpt match {
        case None => Ok(s"Failed to scrape $url")
        case Some(article) => Ok(s"For $url, article:${article.toString}")
      }
    }
  }

  def rescrapeByRegex() = AdminUserPage { implicit request =>
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

  def getScraped(id: Id[NormalizedURI]) = AdminUserPage { implicit request =>
    val articleOption = articleStore.syncGet(id)
    val (uri, scrapeInfoOption) = db.readOnlyReplica { implicit s => (normalizedURIRepo.get(id), scrapeInfoRepo.getByUriId(id)) }
    Ok(html.admin.article(articleOption, uri, scrapeInfoOption))
  }

  def getProxies = AdminUserPage { implicit request =>
    val proxies = db.readOnlyReplica { implicit session => httpProxyRepo.all() }
    Ok(html.admin.proxies(proxies))
  }

  def saveProxies = AdminUserPage { implicit request =>
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

  def getPornDetectorModel = AdminUserPage.async { implicit request =>
    val modelFuture = scraperServiceClient.getPornDetectorModel()
    for (model <- modelFuture) yield Ok(Json.toJson(model))
  }

  def massMarkAsAdult() = AdminUserPage { implicit request =>
    val data: Seq[Id[NormalizedURI]] = Seq(829528, 830819, 908531, 1006924, 1103197, 1120184, 1137738, 1162746, 1162970, 1234789, 1353202, 1353241, 1368209, 1368730, 1369835, 1720903, 1725518, 1752231, 1752501, 1834381, 1837862, 1868889, 2151665, 2613991, 2622609, 2770868, 2818734, 3350098, 3350114, 3350153, 3350161, 3389430, 3389440, 3389442, 3389484, 3389593, 3484477, 3484525, 3484556, 3484569, 3484606, 3539058, 3540520, 3542174, 3542325, 3542332, 3542371, 3542406, 3542423, 3542449, 3542453, 3542469, 3610296, 3612167, 3638605, 3638633, 3638658, 3638714, 3716434, 4137171, 4137293, 4137345, 4151207, 4179670, 4182018, 4182741, 4334430, 4348583, 4348643, 4348735, 4369434, 4379373, 4414452, 4424043, 4528659, 4528963, 4529342, 4529619, 4530016, 4530074, 4530075, 4530914, 4531252, 4531698, 4532199, 4532959, 4533502, 4549027, 4550053, 4550269, 4550418, 4563784, 4620305, 4620316, 4620347, 4620352, 4620370, 4620385, 4620437, 4620498, 4620553, 4620589, 4620600, 4620636, 4620647, 4620714, 4620770, 4621128, 4621202, 4674065, 4735558, 4841361, 4887929, 5009320, 5012386, 5052671, 5052781, 5100366, 5100481, 5102202, 5102592, 5103256, 5103269, 5103962, 5104190, 5104454, 5188585, 5188752, 5188872, 5188944).map(x => Id[NormalizedURI](x.toLong))
    val enum: Enumerator[String] = Concurrent.unicast(onStart = (channel: Concurrent.Channel[String]) =>
      SafeFuture("Uri Adultification") {
        data.foreach { id =>
          db.readWrite { implicit session =>
            uriRepo.updateURIRestriction(id, Some(Restriction.ADULT))
          }
          channel.push(id.id.toString + ",")
          Thread.sleep(100)
        }
        channel.eofAndEnd()
      }
    )
    Ok.chunked(enum)
  }

}

