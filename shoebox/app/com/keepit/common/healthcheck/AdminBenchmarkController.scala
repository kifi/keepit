package com.keepit.common.healthcheck

import scala.util.Random
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._

import play.api.libs.json._
import play.api.Play.current
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial

import com.keepit.search._
import com.keepit.model._
import com.keepit.common.db.Id

import views.html

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.{Inject, Singleton, Provider}

case class BenchmarkResults(cpu: Long, cpuPar: Long, memcacheRead: Double)

object BenchmarkResultsJson {
  implicit val benchmarksResultsFormat = Json.format[BenchmarkResults]
}

@Singleton
class AdminBenchmarkController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  searchServiceClient: SearchServiceClient,
  benchmarkRunner: BenchmarkRunner)
    extends AdminController(actionAuthenticator) {
  import BenchmarkResultsJson._

  def benchmarks = AdminHtmlAction { implicit request =>
    Async {
      for {
        searchBenchmark <- searchServiceClient.benchmarks()
        shoeboxBenchmark <- future { benchmarkRunner.runBenchmark() }
      } yield Ok(html.admin.benchmark(shoeboxBenchmark, searchBenchmark))
    }
  }

}

@Singleton
class CommonBenchmarkController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  benchmarkRunner: BenchmarkRunner)
    extends AdminController(actionAuthenticator) {
  import BenchmarkResultsJson._

  def benchmarksResults = Action { implicit request =>
    Ok(Json.toJson(benchmarkRunner.runBenchmark()))
  }
}

@Singleton
class BenchmarkRunner @Inject() (userIdCache: UserIdCache) {
  def runBenchmark() = BenchmarkResults(cpuBenchmarkTime(), cpuParBenchmarkTime(), memcachedBenchmarkTime())

  private def cpuBenchmarkTime(): Long = {
    val start = System.currentTimeMillis
    (0 to 100) map { i => calc()}
    System.currentTimeMillis - start
  }

  private def cpuParBenchmarkTime(): Long = {
    val start = System.currentTimeMillis
    (0 to 100).par map { i => calc()}
    System.currentTimeMillis - start
  }

  private def calc() {
    var a = 1.1d
    while (a < 10000000d) {a = (a + 0.000001d) * 1.000001}
  }

  private def memcachedBenchmarkTime(): Double = {
    val iterations = 1000
    val start = System.currentTimeMillis
    for (i <- 0 to iterations) { userIdCache.get(UserIdKey(Id[User](1))) }
    (System.currentTimeMillis - start).toDouble / iterations.toDouble
  }
}

