package com.keepit.controllers.website

import controllers.AssetsBuilder
import play.api.mvc.{ AnyContent, Action, Controller }
import scala.concurrent.Future
import com.google.inject.Inject
import com.keepit.inject.FortyTwoConfig

class AboutAssets @Inject() (applicationConfig: FortyTwoConfig) extends AssetsBuilder with Controller {

  val OLD_SITE_REDIRECT_MAP = Map(
    "index.html" -> "mission",
    "team.html" -> "team",
    "culture.html" -> "culture",
    "investors.html" -> "investors",
    "join_us.html" -> "join_us")

  override def at(path: String, file: String): Action[AnyContent] = Action.async { request =>
    if (request.domain.contains("42go")) {
      Future.successful(OLD_SITE_REDIRECT_MAP.get(file) map { redirectFile =>
        MovedPermanently(applicationConfig.applicationBaseUrl + "/about/" + redirectFile)
      } getOrElse NotFound)
    } else if (path == "/public/about_us") {
      val fileWithExt = if (file.contains("/") || file.contains(".")) file else file + ".html"
      super.at(path, fileWithExt).apply(request)
    } else Future.successful(NotFound)
  }
}
