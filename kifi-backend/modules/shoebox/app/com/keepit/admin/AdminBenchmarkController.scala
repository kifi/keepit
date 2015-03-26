package com.keepit.controllers.admin

import scala.util.Random
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent._
import scala.concurrent.duration._

import play.api.libs.json._
import play.api.Play.current
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial

import com.keepit.search._
import com.keepit.common.service._
import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.common.healthcheck._

import views.html

import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.google.inject.Inject

class AdminBenchmarkController @Inject() (
  val userActionsHelper: UserActionsHelper,
  searchServiceClient: SearchServiceClient,
  benchmarkRunner: BenchmarkRunner)
    extends AdminUserActions {
  import BenchmarkResultsJson._

  def benchmarks = AdminUserPage.async { implicit request =>
    val internalPing = pingSearchProcess()
    for {
      searchBenchmark <- searchServiceClient.benchmarks()
      shoeboxBenchmark <- Future { benchmarkRunner.runBenchmark() }
    } yield Ok(html.admin.benchmark(shoeboxBenchmark, searchBenchmark, internalPing))
  }

  private def pingSearchProcess(): Double = {
    val iterations = 100
    val start = System.currentTimeMillis
    for (i <- 0 to iterations) { Await.result(searchServiceClient.version(), Duration(5, SECONDS)) }
    (System.currentTimeMillis - start).toDouble / iterations.toDouble
  }

}

