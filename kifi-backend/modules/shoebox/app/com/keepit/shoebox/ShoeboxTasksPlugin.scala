package com.keepit.shoebox

import akka.actor.ActorSystem
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.shoebox.rover.ShoeboxArticleIngestionActor
import com.keepit.slack.SlackIngestionCommander
import us.theatr.akka.quartz.QuartzActor
import com.keepit.commanders.TwitterSyncCommander
import com.keepit.payments.{ PlanRenewalCommander, PaymentProcessingCommander }

import scala.concurrent.duration._

@Singleton
class ShoeboxTasksPlugin @Inject() (
    twitterSyncCommander: TwitterSyncCommander,
    system: ActorSystem,
    quartz: ActorInstance[QuartzActor],
    articleIngestionActor: ActorInstance[ShoeboxArticleIngestionActor],
    planRenewalCommander: PlanRenewalCommander,
    paymentProcessingCommander: PaymentProcessingCommander,
    slackIngestionCommander: SlackIngestionCommander,
    val scheduling: SchedulingProperties) extends SchedulerPlugin {

  override def onStart() {
    log.info("ShoeboxTasksPlugin onStart")
    scheduleTaskOnOneMachine(system, 3 minute, 1 minutes, "twitter sync") {
      twitterSyncCommander.syncAll()
    }

    scheduleTaskOnOneMachine(system, 5 minute, 1 minutes, "slack ingestion") {
      slackIngestionCommander.ingestAllDue()
    }

    scheduleTaskOnLeader(system, 30 minutes, 30 minutes, "payments processing") {
      planRenewalCommander.processDueRenewals()
      paymentProcessingCommander.processDuePayments()
    }

    scheduleTaskOnOneMachine(articleIngestionActor.system, 3 minutes, 1 minute, articleIngestionActor.ref, ShoeboxArticleIngestionActor.StartIngestion, "ArticleUpdate Ingestion")
  }

}
