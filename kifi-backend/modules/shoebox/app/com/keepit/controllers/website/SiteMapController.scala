package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.seo.SiteMapGenerator
import play.api.libs.concurrent.Execution.Implicits._

class SiteMapController @Inject() (
    airbrake: AirbrakeNotifier,
    val userActionsHelper: UserActionsHelper,
    generator: SiteMapGenerator) extends AdminUserActions {

  // expensive
  def sitemap() = AdminUserPage.async { implicit request =>
    generator.intern() map { elem =>
      Ok(elem).withHeaders("Content-Type" -> "text/xml")
    }
  }
}
