package com.keepit.rover.manager

import javax.inject.{ Inject, Singleton }

import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.rover.manager.ConcurrentTaskProcessingActor.{ Close, IfYouCouldJustGoAhead }

import scala.concurrent.duration._
import scala.util.Random

trait RoverManagerPlugin

@Singleton
class RoverManagerPluginImpl @Inject() (
    ingestionActor: ActorInstance[RoverArticleInfoIngestionActor],
    fetchSchedulingActor: ActorInstance[RoverFetchSchedulingActor],
    fetchingActor: ActorInstance[RoverArticleFetchingActor],
    imageSchedulingActor: ActorInstance[RoverArticleImageSchedulingActor],
    imageProcessingActor: ActorInstance[RoverArticleImageProcessingActor],
    val scheduling: SchedulingProperties) extends RoverManagerPlugin with SchedulerPlugin {

  override def enabled: Boolean = true

  val name: String = getClass.toString

  override def onStart(): Unit = {
    scheduleTaskOnOneMachine(ingestionActor.system, 187 seconds, 1 minute, ingestionActor.ref, IfYouCouldJustGoAhead, "NormalizedURI Ingestion")
    scheduleTaskOnOneMachine(fetchSchedulingActor.system, 200 seconds, 1 minute, fetchSchedulingActor.ref, IfYouCouldJustGoAhead, "Fetch Scheduling")
    scheduleTaskOnAllMachines(fetchingActor.system, (30 + Random.nextInt(60)) seconds, 1 minute, fetchingActor.ref, IfYouCouldJustGoAhead)
    scheduleTaskOnOneMachine(imageSchedulingActor.system, 200 seconds, 1 minute, imageSchedulingActor.ref, IfYouCouldJustGoAhead, "ArticleImage Scheduling")
    scheduleTaskOnAllMachines(imageProcessingActor.system, (30 + Random.nextInt(60)) seconds, 1 minute, imageProcessingActor.ref, IfYouCouldJustGoAhead)

    super.onStart()
  }

  override def onStop(): Unit = {
    Seq(ingestionActor, fetchSchedulingActor, fetchingActor, imageSchedulingActor, imageProcessingActor).foreach(_.ref ! Close)
    super.onStop()
  }
}
