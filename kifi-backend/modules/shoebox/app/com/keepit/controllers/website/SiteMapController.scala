package com.keepit.controllers.website

import play.api.mvc._
import com.google.inject.Inject
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.seo.{ UserSiteMapGenerator, LibrarySiteMapGenerator }
import play.api.libs.concurrent.Execution.Implicits._

class SiteMapController @Inject() (
    airbrake: AirbrakeNotifier,
    val userActionsHelper: UserActionsHelper,
    libraries: LibrarySiteMapGenerator,
    users: UserSiteMapGenerator) extends AdminUserActions {

  def librariesSitemapLegacy() = librariesSitemap()

  def librariesSitemap() = Action.async { implicit request =>
    libraries.intern() map { elem =>
      Ok(elem).withHeaders("Content-Type" -> "text/xml")
    }
  }

  def usersSitemap() = Action.async { implicit request =>
    users.intern() map { elem =>
      Ok(elem).withHeaders("Content-Type" -> "text/xml")
    }
  }
}
