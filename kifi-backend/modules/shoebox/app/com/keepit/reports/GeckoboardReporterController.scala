package com.keepit.reports

import play.api.mvc._
import play.api.data._
import play.api.libs.json.Json
import views.html
import com.google.inject.Inject
import com.keepit.social.{ SocialGraphPlugin, SocialUserRawInfoStore }
import com.keepit.common.controller.ShoeboxServiceController

class GeckoboardReporterController @Inject() (
  geckoboardReporterPlugin: GeckoboardReporterPlugin)
    extends ShoeboxServiceController {

  def refreshAll() = Action { implicit request =>
    geckoboardReporterPlugin.refreshAll()
    Ok("reporting in the background")
  }
}
