package com.keepit.rover.manager

import javax.inject.{ Inject, Singleton }

import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.rover.manager.RoverFetchSchedulingActor.ScheduleFetchTasks
import com.keepit.rover.manager.RoverIngestionActor.StartIngestion

import scala.concurrent.duration._

trait RoverManagerPlugin

@Singleton
class RoverManagerPluginImpl @Inject() (
    ingestionActor: ActorInstance[RoverIngestionActor],
    fetchSchedulingActor: ActorInstance[RoverFetchSchedulingActor],
    val scheduling: SchedulingProperties) extends RoverManagerPlugin with SchedulerPlugin {

  override def enabled: Boolean = true

  val name: String = getClass.toString

  override def onStart(): Unit = {
    scheduleTaskOnOneMachine(ingestionActor.system, 37 seconds, 1 minute, ingestionActor.ref, StartIngestion, "NormalizedURI Ingestion")
    scheduleTaskOnOneMachine(fetchSchedulingActor.system, 119 seconds, 1 minute, fetchSchedulingActor.ref, ScheduleFetchTasks, "Fetch Scheduling")
  }

}
