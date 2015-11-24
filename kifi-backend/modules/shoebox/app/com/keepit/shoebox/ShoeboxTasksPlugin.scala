package com.keepit.shoebox

import akka.actor.ActorSystem
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.shoebox.rover.ShoeboxArticleIngestionActor
import com.keepit.slack.{ SlackCommander, LibraryToSlackChannelPusher, SlackIngestionCommander }
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
    slackCommander: SlackCommander,
    slackIngestionCommander: SlackIngestionCommander,
    libToSlackPusher: LibraryToSlackChannelPusher,
    val scheduling: SchedulingProperties) extends SchedulerPlugin {

  override def onStart() {
    log.info("ShoeboxTasksPlugin onStart")
    scheduleTaskOnOneMachine(system, 3 minute, 1 minutes, "twitter sync") {
      twitterSyncCommander.syncAll()
    }

    scheduleTaskOnLeader(system, 3 minute, 1 minutes, "slack ingestion") {
      slackIngestionCommander.ingestAllDue()
    }

    scheduleTaskOnLeader(system, 1 minute, 5 minutes, "slack filling missing channel ids") {
      slackCommander.fetchMissingChannelIds()
    }

    scheduleTaskOnOneMachine(system, 10 minute, 2 minutes, "slack pushing") {
      libToSlackPusher.findAndPushToLibraries()
    }

    scheduleTaskOnLeader(system, 30 minutes, 30 minutes, "payments processing") {
      planRenewalCommander.processDueRenewals()
      paymentProcessingCommander.processDuePayments()
    }

    scheduleTaskOnOneMachine(articleIngestionActor.system, 3 minutes, 1 minute, articleIngestionActor.ref, ShoeboxArticleIngestionActor.StartIngestion, "ArticleUpdate Ingestion")
  }

}
