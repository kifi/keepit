package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.seo.{ UserSiteMapGenerator, LibrarySiteMapGenerator }
import play.api.libs.concurrent.Execution.Implicits._

class AdminSiteMapController @Inject() (
    airbrake: AirbrakeNotifier,
    val userActionsHelper: UserActionsHelper,
    librarySitemap: LibrarySiteMapGenerator,
    userSitemap: UserSiteMapGenerator) extends AdminUserActions {

  // expensive
  def generateLibrarySitemap() = AdminUserPage.async { implicit request =>
    librarySitemap.generate() map { elem =>
      Ok(elem).withHeaders("Content-Type" -> "text/xml")
    }
  }

  // expensive
  def generateUserSitemap() = AdminUserPage.async { implicit request =>
    userSitemap.generate() map { elem =>
      Ok(elem).withHeaders("Content-Type" -> "text/xml")
    }
  }
}
