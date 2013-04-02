package com.keepit.common.healthcheck

import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.time.RichDateTime
import com.keepit.common.cache.CacheStatistics

import scala.util.Random

import play.api.Play.current
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial

import views.html

import com.google.inject.{Inject, Singleton, Provider}
import com.keepit.common.controller.{WebsiteController,ActionAuthenticator}

@Singleton
class WebsiteHealthController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  healthcheckPlugin: HealthcheckPlugin)
    extends WebsiteController(actionAuthenticator) {

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
