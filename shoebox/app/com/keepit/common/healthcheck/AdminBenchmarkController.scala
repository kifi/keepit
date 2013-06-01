package com.keepit.common.healthcheck

import scala.util.Random

import play.api.Play.current
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial

import views.html

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.{Inject, Singleton, Provider}

@Singleton
class AdminBenchmarkController @Inject() (
  actionAuthenticator: ActionAuthenticator)
    extends AdminController(actionAuthenticator) {

  def benchmarks = AdminHtmlAction { implicit request =>
    Ok(html.admin.benchmark(cpuBenchmarkTime()))
  }

  private def cpuBenchmarkTime(): Long = {
    val start = System.currentTimeMillis
    var a = 1.0
    for (i <- 0 to 100000000) { a = (a + (Random.nextDouble * Random.nextDouble / Random.nextDouble))  }
    System.currentTimeMillis - start
  }
}
