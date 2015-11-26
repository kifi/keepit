package com.keepit.rover.manager

import javax.inject.{ Inject, Singleton }

import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.rover.manager.ConcurrentTaskProcessingActor.{ Close, IfYouCouldJustGoAhead }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

trait RoverManagerPlugin

@Singleton
class RoverManagerPluginImpl @Inject() (
    ingestionActor: ActorInstance[RoverArticleInfoIngestionActor],
    fetchSchedulingActor: ActorInstance[RoverFetchSchedulingActor],
    fetchingActor: ActorInstance[RoverArticleFetchingActor],
    imageSchedulingActor: ActorInstance[RoverArticleImageSchedulingActor],
    imageProcessingActor: ActorInstance[RoverArticleImageProcessingActor],
    implicit val executionContext: ExecutionContext,
    val scheduling: SchedulingProperties) extends RoverManagerPlugin with SchedulerPlugin {

  override def enabled: Boolean = true

  val name: String = getClass.toString

  override def onStart(): Unit = {
    scheduleTaskOnLeader(ingestionActor.system, 400 seconds, 3 minute, ingestionActor.ref, IfYouCouldJustGoAhead)
    scheduleTaskOnLeader(fetchSchedulingActor.system, 450 seconds, 3 minute, fetchSchedulingActor.ref, IfYouCouldJustGoAhead)
    scheduleTaskOnAllMachines(fetchingActor.system, 250 seconds, 3 minute, fetchingActor.ref, IfYouCouldJustGoAhead)
    scheduleTaskOnLeader(imageSchedulingActor.system, 500 seconds, 3 minute, imageSchedulingActor.ref, IfYouCouldJustGoAhead)
    scheduleTaskOnAllMachines(imageProcessingActor.system, 300 seconds, 3 minute, imageProcessingActor.ref, IfYouCouldJustGoAhead)
  }

  override def onStop(): Unit = {
    Seq(ingestionActor, fetchSchedulingActor, fetchingActor, imageSchedulingActor, imageProcessingActor).foreach(_.ref ! Close)
    super.onStop()
  }
}
