package com.keepit.controllers.website

import controllers.AssetsBuilder
import play.api.mvc.{AnyContent, Action, Controller}
import scala.concurrent.Future
import com.google.inject.Inject
import com.keepit.inject.FortyTwoConfig

class AboutAssets @Inject() (applicationConfig: FortyTwoConfig) extends AssetsBuilder with Controller {

  val OLD_SITE_REDIRECT_MAP = Map(
    "index.html" -> "mission.html",
    "team.html" -> "team.html",
    "culture.html" -> "culture.html",
    "investors.html" -> "investors.html",
    "join_us.html" -> "join_us.html")

  override def at(path: String, file: String): Action[AnyContent] = Action.async { request =>
    if (request.domain.contains("42go")) {
      Future.successful(OLD_SITE_REDIRECT_MAP.get(file) map { redirectFile =>
        MovedPermanently(applicationConfig.applicationBaseUrl + "/about/" + redirectFile)
      } getOrElse NotFound)
    } else if (path == "/public/about_us") {
      val extension = ".html"
      val fileWithExtension = if (!file.endsWith(extension) && !file.contains("/") && !file.contains(".")) {
        file + extension
      } else file
      super.at(path, fileWithExtension).apply(request)
    } else Future.successful(NotFound)
  }
}
