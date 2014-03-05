package com.keepit.controllers.admin

import com.keepit.common.controller.AdminController
import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import views.html
import com.keepit.scraper.ScraperServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.model.NormalizedURIRepo
import com.keepit.common.db.slick.Database
import com.keepit.model.Restriction

class AdminPornDetectorController @Inject()(
  scraper: ScraperServiceClient,
  db: Database,
  uriRepo: NormalizedURIRepo,
  actionAuthenticator: ActionAuthenticator
) extends AdminController(actionAuthenticator) {

  private def tokenize(query: String): Array[String] = {
    query.split("[^a-zA-Z0-9]").filter(!_.isEmpty).map{_.toLowerCase}
  }

  def index() = AdminHtmlAction.authenticated{ implicit request =>
    Ok(html.admin.pornDetector())
  }

  def detect() = AdminHtmlAction.authenticated{ implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val text = body.get("query").get
    val numBlocks = tokenize(text).sliding(10, 5).size
    val badTexts = Await.result(scraper.detectPorn(text), 15 seconds)
    val badInfo = badTexts.map{ x => x._1 + " ---> " + x._2}.mkString("\n")
    val msg = if (badTexts.size == 0) "input text is clean" else s"${badTexts.size} out of ${numBlocks} blocks look suspicious:\n" + badInfo
    Ok(msg.replaceAll("\n","\n<br>"))
  }

  def pornUrisView(page: Int) = AdminHtmlAction.authenticated{ implicit request =>
    val uris = db.readOnly{implicit s => uriRepo.getRestrictedURIs(Restriction.ADULT)}.sortBy(-_.updatedAt.getMillis())
    val PAGE_SIZE = 50
    val pageCount = (uris.size*1.0 / PAGE_SIZE).ceil.toInt

    Ok(html.admin.pornUris(uris, uris.size, page, pageCount))
  }
}
