package com.keepit.eliza

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.eliza.integrity.ElizaDataIntegrityActors
import com.keepit.eliza.shoebox.ElizaKeepIngestingActor
import com.kifi.juggle.ConcurrentTaskProcessingActor.{ Close, IfYouCouldJustGoAhead }

import scala.concurrent.duration._

@Singleton
class ElizaTasksPlugin @Inject() (
  dataIntegrity: ElizaDataIntegrityActors,
  keepIngestingActor: ActorInstance[ElizaKeepIngestingActor],
  val scheduling: SchedulingProperties)
    extends SchedulerPlugin {

  override def onStart() {
    log.info("ElizaTasksPlugin onStart")
    scheduleTaskOnLeader(keepIngestingActor.system, 3 minutes, 1 minute, keepIngestingActor.ref, IfYouCouldJustGoAhead)

    scheduleTaskOnLeader(keepIngestingActor.system, 3 minutes, 1 minute, dataIntegrity.messageThreadByMessage.ref, IfYouCouldJustGoAhead)
  }

  override def onStop() {
    Seq(
      keepIngestingActor,
      dataIntegrity.messageThreadByMessage
    ).foreach(_.ref ! Close)
    super.onStop()
  }

}
