package com.keepit.controllers.admin

import com.keepit.common.controller.AdminController
import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import views.html
import com.keepit.scraper.ScraperServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._

class AdminPornDetectorController @Inject()(
  scraper: ScraperServiceClient,
  actionAuthenticator: ActionAuthenticator
) extends AdminController(actionAuthenticator) {
  def index() = AdminHtmlAction.authenticated{ implicit request =>
    Ok(html.admin.pornDetector())
  }

  def detect() = AdminHtmlAction.authenticated{ implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val text = body.get("query").get
    val badTexts = Await.result(scraper.detectPorn(text), 5 seconds)
    val msg = badTexts.toString
    Ok(msg)
  }
}
