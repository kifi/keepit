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

case class BenchmarkResults(cpu: Long, memcacheRead: Long)

object BenchmarkResultsJson {
  implicit val benchmarksResultsFormat = Json.format[BenchmarkResults]
}

@Singleton
class AdminBenchmarkController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  searchServiceClient: SearchServiceClient,
  userIdCache: UserIdCache)
    extends AdminController(actionAuthenticator) {
  import BenchmarkResultsJson._

  def benchmarks = AdminHtmlAction { implicit request =>
    Async {
      for {
        searchBenchmark <- searchServiceClient.benchmarks()
        shoeboxBenchmark <- future { runBenchmark() }
      } yield Ok(html.admin.benchmark(shoeboxBenchmark, searchBenchmark))
    }
  }

  def benchmarksResults = Action { implicit request =>
    Ok(Json.toJson(runBenchmark()))
  }

  private def runBenchmark() = BenchmarkResults(cpuBenchmarkTime(), memcachedBenchmarkTime())

  private def cpuBenchmarkTime(): Long = {
    val start = System.currentTimeMillis
    var a = 1.0
    for (i <- 0 to 100000000) { a = (a + (Random.nextDouble * Random.nextDouble / Random.nextDouble))  }
    System.currentTimeMillis - start
  }

  private def memcachedBenchmarkTime(): Long = {
    val iterations = 100
    val start = System.currentTimeMillis
    for (i <- 0 to iterations) { userIdCache.get(UserIdKey(Id[User](1))) }
    (System.currentTimeMillis - start) / iterations
  }
}

