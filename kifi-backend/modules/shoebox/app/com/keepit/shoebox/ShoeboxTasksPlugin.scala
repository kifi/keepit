package com.keepit.shoebox

import akka.actor.ActorSystem
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.time._
import us.theatr.akka.quartz.QuartzActor
import com.keepit.commanders.TwitterSyncCommander

import scala.concurrent.duration._

object ShoeboxTasks {
  val twitterSync = "complete data ingestion"
}

@Singleton
class ShoeboxTasksPlugin @Inject() (
    twitterSyncCommander: TwitterSyncCommander,
    system: ActorSystem,
    quartz: ActorInstance[QuartzActor],
    val scheduling: SchedulingProperties) extends SchedulerPlugin {

  import ShoeboxTasks._

  override def onStart() {
    log.info("ShoeboxTasksPlugin onStart")
    scheduleTaskOnOneMachine(system, 5 minutes, 20 minutes, twitterSync) {
      twitterSyncCommander.syncAll()
    }
  }

}
