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
    val articleOption = articleStore.get(id)
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
    val data: Seq[Id[NormalizedURI]] = Seq(721632, 933714, 962284, 962286, 962287, 962288, 962579, 977017, 977172, 977462, 977849, 979623, 1006901, 1103487, 1162737, 1162981, 1162985, 1163023, 1274217, 1715054, 1715062, 1756280, 1864840, 1868816, 1868957, 2155626, 2155845, 2612789, 2612894, 2613801, 2614565, 2614983, 2615149, 2615496, 2616079, 2616149, 2616906, 2617454, 2618676, 2619480, 2619641, 2619824, 2621559, 2621633, 2622645, 2623246, 2881506, 2881522, 2881533, 2881698, 2881703, 2881806, 3216454, 3350214, 3389347, 3389354, 3389356, 3389375, 3389386, 3389388, 3389409, 3389486, 3389502, 3484470, 3489024, 3497711, 3509743, 3509875, 3510632, 3510771, 3511059, 3511154, 3523515, 3561038, 3565373, 3606626, 3610188, 3610951, 3611514, 3611690, 3612292, 3612607, 4001447, 4150865, 4179581, 4180795, 4181591, 4188559, 4293114, 4467200, 4510780, 4522743, 4528647, 4528653, 4528706, 4528751, 4528764, 4528779, 4528885, 4528916, 4528937, 4528951, 4529000, 4529017, 4529048, 4529056, 4529087, 4529099, 4529102, 4529108, 4529113, 4529134, 4529195, 4529196, 4529197, 4529254, 4529284, 4529319, 4529376, 4529458, 4529462, 4529466, 4529489, 4529515, 4529541, 4529544, 4529550, 4529575, 4529576, 4529588, 4529649, 4529652, 4529733, 4529738, 4529740, 4529770, 4529787, 4529803, 4529816, 4529931, 4529996, 4530002, 4530052, 4530056, 4530065, 4530091, 4530126, 4530156, 4530157, 4530158, 4530160, 4530204, 4530232, 4530234, 4530324, 4530341, 4530346, 4530358, 4530365, 4530448, 4530466, 4530467, 4530468, 4530483, 4530487, 4530504, 4530509, 4530516, 4530518, 4530582, 4530610, 4530616, 4530645, 4530649, 4530672, 4530709, 4530722, 4530728, 4530736, 4530748, 4530758, 4530777, 4530836, 4530867, 4530868, 4530875, 4530911, 4530922, 4530972, 4530986, 4530993, 4531008, 4531011, 4531019, 4531024, 4531049, 4531076, 4531111, 4531136, 4531138, 4531144, 4531193, 4531202, 4531236, 4531311, 4531316, 4531384, 4531397, 4531468, 4531501, 4531503, 4531557, 4531586, 4531628, 4531643, 4531658, 4531677, 4531701, 4531711, 4531738, 4531799, 4531810, 4531833, 4531873, 4531915, 4531920, 4531945, 4531958, 4531981, 4532013, 4532029, 4532094, 4532096, 4532114, 4532210, 4532258, 4532342, 4532394, 4532456, 4532504, 4532530, 4532583, 4532629, 4532755, 4532760, 4532808, 4532842, 4533124, 4533215, 4533220, 4533224, 4533332, 4533491, 4533761, 4533766, 4533840, 4533863, 4533948, 4533955, 4534051, 4534084, 4534153, 4534266, 4534325, 4534331, 4534380, 4534408, 4534451, 4534499, 4534523, 4534584, 4534672, 4534683, 4563640, 4568629, 4620358, 4620376, 4620388, 4620415, 4620417, 4620425, 4620443, 4620446, 4620457, 4620459, 4620479, 4620485, 4620533, 4620605, 4620652, 4620691, 4620692, 4620693, 4620705, 4621022, 4621027, 4688153, 4702965, 4715693, 4771774, 4771844, 4771849, 4771879, 4771899, 4771943, 4806045, 4817699, 4820633, 4898982, 4906681, 4907079, 4907343, 4922988, 4990013, 4990160, 5011664, 5052804, 5100031, 5100092, 5100096, 5100104, 5100241, 5100287, 5100338, 5100440, 5100515, 5100559, 5100647, 5100732, 5100861, 5100872, 5100878, 5100914, 5101003, 5101016, 5101027, 5101029, 5101110, 5101176, 5101178, 5101180, 5101182, 5101213, 5101226, 5101270, 5101289, 5101419, 5101433, 5101440, 5101448, 5101564, 5101566, 5101584, 5101661, 5101716, 5101724, 5101732, 5101743, 5101785, 5101825, 5101899, 5101965, 5101989, 5102096, 5102129, 5102233, 5102248, 5102330, 5102343, 5102390, 5102396, 5102468, 5102489, 5102501, 5102517, 5102668, 5102748, 5102766, 5102827, 5102855, 5102859, 5103002, 5103013, 5103021, 5103059, 5103131, 5103194, 5103201, 5103209, 5103224, 5103236, 5103249, 5103299, 5103301, 5103335, 5103377, 5103437, 5103521, 5103678, 5103689, 5103718, 5103737, 5103805, 5103852, 5103886, 5104005, 5104098, 5104145, 5104214, 5104217, 5104295, 5104402, 5104404, 5104458, 5141669, 5152787, 5179909, 5188968, 5277018, 5277029, 5277053, 5277060, 5277062, 5277064, 5277066, 5277071, 5277135, 5278034, 5278306, 5278713, 5280153).map(x => Id[NormalizedURI](x.toLong))
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

