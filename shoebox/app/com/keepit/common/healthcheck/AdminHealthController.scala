package com.keepit.common.healthcheck

import com.keepit.common.time.RichDateTime
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.cache.CacheStatistics

import scala.util.Random

import play.api.Play.current
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial

import views.html

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.{Inject, Singleton, Provider}

@Singleton
class AdminHealthController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  healthcheckPlugin: HealthcheckPlugin,
  services: FortyTwoServices,
  cacheStatistics: CacheStatistics)
    extends AdminController(actionAuthenticator) {

  def serviceView = AdminHtmlAction { implicit request =>
    val errorCount = healthcheckPlugin.errorCount
    val recentErrors = healthcheckPlugin.errors()
    val cacheStats = cacheStatistics.getStatistics
    val (totalHits, totalMisses, totalSets) = (cacheStats.map(_._2).sum, cacheStats.map(_._3).sum, cacheStats.map(_._4).sum)
    Ok(html.admin.serverInfo(services.currentService, services.currentVersion, services.compilationTime.toStandardTimeString,
        services.started.toStandardTimeString, errorCount, recentErrors, cacheStats, totalHits, totalMisses, totalSets))
  }

  def getErrors() = AdminHtmlAction { implicit request =>
    Ok(healthcheckPlugin.errors().mkString("\n"))
  }

  def reportErrors() = AdminHtmlAction { implicit request =>
    healthcheckPlugin.reportErrors()
    Ok("reported")
  }

  def resetErrorCount() = AdminHtmlAction { implicit request =>
    healthcheckPlugin.resetErrorCount
    Redirect(routes.AdminHealthController.serviceView)
  }

  //randomly creates one of two exceptions, each time with a random exception message
  def causeError() = AdminHtmlAction { implicit request =>
    if (Random.nextBoolean) {
      // throwing a X/0 exception. its a fixed stack exception with random message text
      (Random.nextInt) / 0
    }
    // throwing an array out bound exception. its a fixed stack exception with random message text
    (new Array[Int](1))(Random.nextInt + 1) = 1
    Ok("You cannot see this :-P ")
  }
}
