package com.keepit.commanders

import com.keepit.model.{ ExperimentType, User, ProbabilisticExperimentGenerator, ProbabilisticExperimentGeneratorAllCache, ProbabilisticExperimentGeneratorAllKey }
import com.keepit.common.db.Id
import com.keepit.common.concurrent.PimpMyFuture._
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.cache.TransactionalCaching.Implicits._
import com.keepit.common.healthcheck.AirbrakeNotifier

import com.google.common.cache.{ CacheBuilder, CacheLoader }
import com.google.common.util.concurrent.ListenableFuture

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._

import java.util.concurrent.{ TimeUnit, TimeoutException }

import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait UserExperimentCommander {

  protected val monitoredAwait: MonitoredAwait
  protected val generatorCache: ProbabilisticExperimentGeneratorAllCache
  protected val airbrake: AirbrakeNotifier

  def getExperimentGenerators(): Future[Seq[ProbabilisticExperimentGenerator]]

  private def getExperimentGeneratorsWithCache(): Future[Seq[ProbabilisticExperimentGenerator]] = {
    generatorCache.getOrElseFuture(ProbabilisticExperimentGeneratorAllKey) {
      getExperimentGenerators()
    }
  }

  //Note(Stephen): Currently this cache ever only stores one value, so the Key is always the string "key"
  private val innerGeneratorCache = CacheBuilder.newBuilder().concurrencyLevel(1).maximumSize(1).refreshAfterWrite(5, TimeUnit.SECONDS).build(new CacheLoader[String, Seq[ProbabilisticExperimentGenerator]] {
    override def load(key: String): Seq[ProbabilisticExperimentGenerator] = monitoredAwait.result(getExperimentGeneratorsWithCache(), 1 seconds, "Failed to get PEGens in time", Seq())
    override def reload(key: String, value: Seq[ProbabilisticExperimentGenerator]): ListenableFuture[Seq[ProbabilisticExperimentGenerator]] = getExperimentGeneratorsWithCache().asListenableFuture

  })

  def addDynamicExperiments(userId: Id[User], statics: Set[ExperimentType]): Set[ExperimentType] = {
    try {
      val newExperiments = innerGeneratorCache.get("key").map(_(userId, statics)).filter(_.isDefined).map(_.get).filter(_ != ExperimentType.ADMIN)
      statics ++ newExperiments
    } catch {
      case t: Throwable => {
        airbrake.notify("Failed to resolve dynamic experiments", t)
        statics
      }
    }

  }

}
