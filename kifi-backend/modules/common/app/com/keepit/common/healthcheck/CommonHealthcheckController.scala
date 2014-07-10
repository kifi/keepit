package com.keepit.common.healthcheck

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
import com.keepit.common.cache.FortyTwoCachePlugin
import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.google.inject.Inject

case class BenchmarkResults(cpu: Long, cpuPar: Long, memcacheRead: Double)

object BenchmarkResultsJson {
  implicit val benchmarksResultsFormat = Json.format[BenchmarkResults]
}

class CommonBenchmarkController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  benchmarkRunner: BenchmarkRunner,
  fortyTwoServices: FortyTwoServices)
    extends AdminController(actionAuthenticator) {
  import BenchmarkResultsJson._

  def benchmarksResults = Action { implicit request =>
    Ok(Json.toJson(benchmarkRunner.runBenchmark()))
  }

  def version() = Action { implicit request =>
    Ok(fortyTwoServices.currentVersion.toString)
  }
}

class BenchmarkRunner @Inject() (cache: FortyTwoCachePlugin) {
  def runBenchmark() = BenchmarkResults(cpuBenchmarkTime(), cpuParBenchmarkTime(), memcachedBenchmarkTime())

  private def cpuBenchmarkTime(): Long = {
    val start = System.currentTimeMillis
    (0 to 100) map { i => calc() }
    System.currentTimeMillis - start
  }

  private def cpuParBenchmarkTime(): Long = {
    val start = System.currentTimeMillis
    (0 to 100).par map { i => calc() }
    System.currentTimeMillis - start
  }

  private def calc() {
    var a = 1.1d
    while (a < 10000000d) { a = (a + 0.000001d) * 1.000001 }
  }

  private def memcachedBenchmarkTime(): Double = {
    val iterations = 1000
    val start = System.currentTimeMillis
    for (i <- 0 to iterations) { cache.get(UserExperimentUserIdKey(Id[User](1)).toString) }
    (System.currentTimeMillis - start).toDouble / iterations.toDouble
  }
}
