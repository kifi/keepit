package com.keepit.rover.manager

import javax.inject.{ Inject, Singleton }

import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.slick.Database
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.rover.manager.RoverArticleFetchingActor.{ Close, StartPullingTasks }
import com.keepit.rover.manager.RoverFetchSchedulingActor.ScheduleFetchTasks
import com.keepit.rover.manager.RoverIngestionActor.StartIngestion
import com.keepit.rover.model.{ ArticleInfoRepoImpl }

import scala.concurrent.duration._
import scala.util.Random
import scala.concurrent.ExecutionContext.Implicits._

trait RoverManagerPlugin

@Singleton
class RoverManagerPluginImpl @Inject() (
    ingestionActor: ActorInstance[RoverIngestionActor],
    fetchSchedulingActor: ActorInstance[RoverFetchSchedulingActor],
    fetchingActor: ActorInstance[RoverArticleFetchingActor],
    val scheduling: SchedulingProperties,
    db: Database,
    articleInfoRepo: ArticleInfoRepoImpl) extends RoverManagerPlugin with SchedulerPlugin {

  override def enabled: Boolean = true

  val name: String = getClass.toString

  override def onStart(): Unit = {
    scheduleTaskOnOneMachine(ingestionActor.system, 187 seconds, 1 minute, ingestionActor.ref, StartIngestion, "NormalizedURI Ingestion")
    scheduleTaskOnOneMachine(fetchSchedulingActor.system, 200 seconds, 1 minute, fetchSchedulingActor.ref, ScheduleFetchTasks, "Fetch Scheduling")
    scheduleTaskOnAllMachines(fetchingActor.system, (30 + Random.nextInt(60)) seconds, 1 minute, fetchingActor.ref, StartPullingTasks)

    SafeFuture("Backfilling domain names") {
      val pageSize = 100
      var page = 0
      var lastPageSize = 0
      do {
        lastPageSize = db.readWrite { implicit session =>
          articleInfoRepo.setDomains(page, pageSize)
        }
        page += 1
      } while (lastPageSize > 0)
    }

    super.onStart()
  }

  override def onStop(): Unit = {
    fetchingActor.ref ! Close
    super.onStop()
  }
}
