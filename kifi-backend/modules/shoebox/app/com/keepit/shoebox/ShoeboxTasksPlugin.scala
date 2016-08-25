package com.keepit.shoebox

import akka.actor.ActorSystem
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.export.FullExportProcessingActor
import com.keepit.shoebox.eliza.ShoeboxMessageIngestionActor
import com.keepit.shoebox.rover.ShoeboxArticleIngestionActor
import com.kifi.juggle.ConcurrentTaskProcessingActor.{ Close, IfYouCouldJustGoAhead }
import us.theatr.akka.quartz.QuartzActor

import scala.concurrent.duration._

@Singleton
class ShoeboxTasksPlugin @Inject() (
    system: ActorSystem,
    quartz: ActorInstance[QuartzActor],
    articleIngestionActor: ActorInstance[ShoeboxArticleIngestionActor],
    messageIngestionActor: ActorInstance[ShoeboxMessageIngestionActor],
    exportingActor: ActorInstance[FullExportProcessingActor],
    val scheduling: SchedulingProperties) extends SchedulerPlugin {

  override def onStart() { //keep me alive - ish!
    log.info("ShoeboxTasksPlugin onStart")
    scheduleTaskOnLeader(articleIngestionActor.system, 3 minutes, 1 minute, articleIngestionActor.ref, ShoeboxArticleIngestionActor.StartIngestion)
    scheduleTaskOnLeader(messageIngestionActor.system, 500 seconds, 3 minute, messageIngestionActor.ref, IfYouCouldJustGoAhead)
    scheduleTaskOnAllMachines(exportingActor.system, 3 minute, 1 minute, exportingActor.ref, IfYouCouldJustGoAhead)
  }

  override def onStop() {
    Seq(articleIngestionActor, messageIngestionActor, exportingActor).foreach(_.ref ! Close)
    super.onStop()
  }

}
