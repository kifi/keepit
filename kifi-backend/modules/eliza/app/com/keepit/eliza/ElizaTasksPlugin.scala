package com.keepit.eliza

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.eliza.shoebox.ElizaKeepIngestingActor
import com.kifi.juggle.ConcurrentTaskProcessingActor.{ Close, IfYouCouldJustGoAhead }

import scala.concurrent.duration._

@Singleton
class ElizaTasksPlugin @Inject() (
  keepIngestingActor: ActorInstance[ElizaKeepIngestingActor],
  val scheduling: SchedulingProperties)
    extends SchedulerPlugin {

  override def onStart() {
    log.info("ElizaTasksPlugin onStart")
    scheduleTaskOnLeader(keepIngestingActor.system, 3 minutes, 1 minute, keepIngestingActor.ref, IfYouCouldJustGoAhead)
  }

  override def onStop() {
    Seq(
      keepIngestingActor
    ).foreach(_.ref ! Close)
    super.onStop()
  }

}
