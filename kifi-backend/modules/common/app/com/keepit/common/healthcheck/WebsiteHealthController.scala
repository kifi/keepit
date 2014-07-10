package com.keepit.common.healthcheck

import com.keepit.common.service.{ ServiceType, FortyTwoServices }
import com.keepit.common.time.RichDateTime
import scala.util.Random

import play.api.Play.current
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial

import views.html

import com.google.inject.Inject
import com.keepit.common.controller.{ WebsiteController, ActionAuthenticator }

class WebsiteHealthController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  healthcheckPlugin: HealthcheckPlugin,
  service: FortyTwoServices)
    extends WebsiteController(actionAuthenticator) {

  override lazy val serviceType: ServiceType = service.currentService

  def ping() = Action { implicit request =>
    Ok(healthcheckPlugin.errorCount.toString)
  }

  def isHealthy = Action { implicit request =>
    val error = healthcheckPlugin.errorCount

    error match {
      case 0 => Ok("Good!")
      case s: Int => Status(418)(error.toString)
    }
  }
}
