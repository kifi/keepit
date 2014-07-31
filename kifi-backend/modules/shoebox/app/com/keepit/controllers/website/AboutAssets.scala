package com.keepit.controllers.website

import controllers.AssetsBuilder
import play.api.mvc.{ AnyContent, Action }
import scala.concurrent.Future
import com.google.inject.Inject
import com.keepit.inject.FortyTwoConfig
import com.keepit.common.controller.ShoeboxServiceController

class AboutAssets @Inject() (applicationConfig: FortyTwoConfig) extends AssetsBuilder with ShoeboxServiceController {

  val HtmlRedirects = Set("mission.html", "team.html", "culture.html", "investors.html", "join_us.html")
  val OldSiteRedirects = Map(
    "index.html" -> "mission",
    "team.html" -> "team",
    "culture.html" -> "culture",
    "investors.html" -> "investors",
    "join_us.html" -> "join_us")

  override def at(path: String, file: String): Action[AnyContent] = Action.async { request =>
    if (request.domain.contains("42go")) {
      Future.successful(OldSiteRedirects.get(file) map { redirectFile =>
        MovedPermanently(applicationConfig.applicationBaseUrl + "/about/" + redirectFile)
      } getOrElse NotFound)
    } else if (path == "/public/about_us") {
      if (HtmlRedirects.contains(file)) {
        val qs = request.rawQueryString
        Future.successful(MovedPermanently(request.path.dropRight(5) + (if (qs.isEmpty) "" else "?" + qs)))
      } else if (file.contains("/") || file.contains(".")) {
        super.at(path, file).apply(request)
      } else {
        super.at(path, file + ".html").apply(request)
      }
    } else Future.successful(NotFound)
  }
}
