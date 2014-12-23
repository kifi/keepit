package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.seo.LibrarySiteMapGenerator
import play.api.libs.concurrent.Execution.Implicits._

class AdminSiteMapController @Inject() (
    airbrake: AirbrakeNotifier,
    val userActionsHelper: UserActionsHelper,
    generator: LibrarySiteMapGenerator) extends AdminUserActions {

  // expensive
  def generate() = AdminUserPage.async { implicit request =>
    generator.generate() map { elem =>
      Ok(elem).withHeaders("Content-Type" -> "text/xml")
    }
  }
}
