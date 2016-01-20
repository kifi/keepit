package com.keepit.shoebox

import akka.actor.ActorSystem
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.shoebox.eliza.ShoeboxMessageIngestionActor
import com.keepit.shoebox.rover.ShoeboxArticleIngestionActor
import com.keepit.slack._
import us.theatr.akka.quartz.QuartzActor
import com.keepit.commanders.TwitterSyncCommander
import com.keepit.payments.{ PlanRenewalCommander, PaymentProcessingCommander }
import com.kifi.juggle.ConcurrentTaskProcessingActor.{ Close, IfYouCouldJustGoAhead }

import scala.concurrent.duration._

@Singleton
class ShoeboxTasksPlugin @Inject() (
    twitterSyncCommander: TwitterSyncCommander,
    system: ActorSystem,
    quartz: ActorInstance[QuartzActor],
    articleIngestionActor: ActorInstance[ShoeboxArticleIngestionActor],
    messageIngestionActor: ActorInstance[ShoeboxMessageIngestionActor],
    slackIngestingActor: ActorInstance[SlackIngestingActor],
    planRenewalCommander: PlanRenewalCommander,
    paymentProcessingCommander: PaymentProcessingCommander,
    slackIntegrationCommander: SlackIntegrationCommander,
    slackDigestNotifier: SlackDigestNotifier,
    libToSlackPusher: LibraryToSlackChannelPusher,
    val scheduling: SchedulingProperties) extends SchedulerPlugin {

  override def onStart() {
    log.info("ShoeboxTasksPlugin onStart")
    scheduleTaskOnOneMachine(system, 3 minute, 1 minutes, "twitter sync") {
      twitterSyncCommander.syncAll()
    }

    scheduleTaskOnLeader(system, 1 minute, 30 seconds, "fetching missing Slack channel ids") {
      slackIntegrationCommander.fetchMissingChannelIds()
    }

    scheduleTaskOnOneMachine(system, 1 minute, 20 seconds, "slack pushing") {
      libToSlackPusher.findAndPushUpdatesForRipestIntegrations()
    }

    // TODO(ryan): make these way slower, no need to run this that often
    scheduleTaskOnOneMachine(system, 5 minutes, 1 minute, "slack digests") {
      slackDigestNotifier.pushDigestNotificationsForRipeTeams()
      slackDigestNotifier.pushDigestNotificationsForRipeChannels()
    }

    scheduleTaskOnLeader(system, 30 minutes, 30 minutes, "payments processing") {
      planRenewalCommander.processDueRenewals()
      paymentProcessingCommander.processDuePayments()
    }

    scheduleTaskOnLeader(articleIngestionActor.system, 3 minutes, 1 minute, articleIngestionActor.ref, ShoeboxArticleIngestionActor.StartIngestion)
    scheduleTaskOnLeader(messageIngestionActor.system, 500 seconds, 3 minute, messageIngestionActor.ref, IfYouCouldJustGoAhead)
    scheduleTaskOnLeader(slackIngestingActor.system, 3 minute, 20 seconds, slackIngestingActor.ref, IfYouCouldJustGoAhead)
  }

  override def onStop() {
    Seq(messageIngestionActor, slackIngestingActor).foreach(_.ref ! Close)
    super.onStop()
  }

}
