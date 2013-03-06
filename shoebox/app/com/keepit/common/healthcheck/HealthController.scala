package com.keepit.common.healthcheck

import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.logging.Logging
import com.keepit.common.time.RichDateTime
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.cache.CacheStatistics
import com.keepit.inject._

import play.api.Play.current
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial

import views.html

object HealthController extends FortyTwoController {

  def serviceView = AdminHtmlAction { implicit request =>
    val healthcheckPlugin = inject[HealthcheckPlugin]
    val errorCount = healthcheckPlugin.errorCount
    val recentErrors = healthcheckPlugin.errors()
    val services = inject[FortyTwoServices]
    val cacheStats = inject[CacheStatistics].getStatistics
    val (totalHits, totalMisses, totalSets) = (cacheStats.map(_._2).sum, cacheStats.map(_._3).sum, cacheStats.map(_._4).sum)
    Ok(html.admin.serverInfo(services.currentService, services.currentVersion, services.compilationTime.toStandardTimeString,
        services.started.toStandardTimeString, errorCount, recentErrors, cacheStats, totalHits, totalMisses, totalSets))
  }

  def ping() = Action { implicit request =>
    Ok(inject[HealthcheckPlugin].errorCount.toString)
  }

  def isHealthy = Action { implicit request =>
    val error = inject[HealthcheckPlugin].errorCount

    error match {
      case 0 => Ok("Good!")
      case s: Int => Status(418)(error.toString)
    }
  }

  def causeError() = Action { implicit request =>
    0/0 // The realest fake error imaginable // Technically, this error is ∉ ℝ
    Ok("You cannot see this.")
  }

  def getErrors() = AdminHtmlAction { implicit request =>
    Ok(inject[HealthcheckPlugin].errors().mkString("\n"))
  }

  def resetErrorCount() = AdminHtmlAction { implicit request =>
    inject[HealthcheckPlugin].resetErrorCount

    Redirect(routes.HealthController.serviceView)
  }
}
