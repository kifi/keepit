package com.keepit.rover.manager

import javax.inject.{ Inject, Singleton }

import com.keepit.common.actor.ActorInstance
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.rover.commanders.ImageProcessingCommander
import com.keepit.rover.manager.ConcurrentTaskProcessingActor.{ Close, IfYouCouldJustGoAhead }

import scala.concurrent.duration._
import scala.util.Random

trait RoverManagerPlugin

@Singleton
class RoverManagerPluginImpl @Inject() (
    ingestionActor: ActorInstance[RoverIngestionActor],
    fetchSchedulingActor: ActorInstance[RoverFetchSchedulingActor],
    fetchingActor: ActorInstance[RoverArticleFetchingActor],
    imageSchedulingActor: ActorInstance[RoverArticleImageSchedulingActor],
    imageProcessingActor: ActorInstance[RoverArticleImageProcessingActor],
    imageInfoCommander: ImageProcessingCommander,
    instance: AmazonInstanceInfo,
    val scheduling: SchedulingProperties) extends RoverManagerPlugin with SchedulerPlugin {

  override def enabled: Boolean = true

  val name: String = getClass.toString

  override def onStart(): Unit = {
    scheduleTaskOnOneMachine(ingestionActor.system, 187 seconds, 1 minute, ingestionActor.ref, IfYouCouldJustGoAhead, "NormalizedURI Ingestion")
    scheduleTaskOnOneMachine(fetchSchedulingActor.system, 200 seconds, 1 minute, fetchSchedulingActor.ref, IfYouCouldJustGoAhead, "Fetch Scheduling")
    scheduleTaskOnAllMachines(fetchingActor.system, (30 + Random.nextInt(60)) seconds, 1 minute, fetchingActor.ref, IfYouCouldJustGoAhead)
    // scheduleTaskOnOneMachine(imageSchedulingActor.system, 200 seconds, 1 minute, fetchSchedulingActor.ref, IfYouCouldJustGoAhead, "ArticleImage Scheduling") todo(Léo): Turn on.
    // scheduleTaskOnAllMachines(imageProcessingActor.system, (30 + Random.nextInt(60)) seconds, 1 minute, fetchingActor.ref, IfYouCouldJustGoAhead) todo(Léo): Turn on.

    if (instance.getName == "rover-demand-1") {
      log.info("Starting ImageInfo ingestion")
      imageInfoCommander.ingestEmbedlyImagesFromShoebox()
    }
    super.onStart()
  }

  override def onStop(): Unit = {
    Seq(ingestionActor, fetchSchedulingActor, fetchingActor, imageSchedulingActor, imageProcessingActor).foreach(_.ref ! Close)
    super.onStop()
  }
}
