package com.keepit.shoebox

import akka.actor.ActorSystem
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.shoebox.rover.ShoeboxArticleIngestionActor
import us.theatr.akka.quartz.QuartzActor
import com.keepit.commanders.TwitterSyncCommander
import com.keepit.payments.PaymentProcessingCommander

import scala.concurrent.duration._

object ShoeboxTasks {
  val twitterSync = "complete data ingestion"
  val paymentsProcessing = "payments processing"
}

@Singleton
class ShoeboxTasksPlugin @Inject() (
    twitterSyncCommander: TwitterSyncCommander,
    system: ActorSystem,
    quartz: ActorInstance[QuartzActor],
    articleIngestionActor: ActorInstance[ShoeboxArticleIngestionActor],
    paymentProcessingCommander: PaymentProcessingCommander,
    val scheduling: SchedulingProperties) extends SchedulerPlugin {

  import ShoeboxTasks._

  override def onStart() {
    log.info("ShoeboxTasksPlugin onStart")
    scheduleTaskOnOneMachine(system, 3 minute, 1 minutes, twitterSync) {
      twitterSyncCommander.syncAll()
    }

    scheduleTaskOnOneMachine(system, 30 minute, 4 hours, paymentsProcessing) {
      paymentProcessingCommander.processAllBilling()
    }

    scheduleTaskOnOneMachine(articleIngestionActor.system, 3 minutes, 1 minute, articleIngestionActor.ref, ShoeboxArticleIngestionActor.StartIngestion, "ArticleUpdate Ingestion")
  }

}
