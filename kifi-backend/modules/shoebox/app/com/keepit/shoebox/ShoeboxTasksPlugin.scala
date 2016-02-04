package com.keepit.shoebox

import akka.actor.ActorSystem
import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.TwitterSyncCommander
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.payments.{ PaymentProcessingCommander, PlanRenewalCommander }
import com.keepit.shoebox.eliza.ShoeboxMessageIngestionActor
import com.keepit.shoebox.rover.ShoeboxArticleIngestionActor
import com.keepit.slack._
import com.kifi.juggle.ConcurrentTaskProcessingActor.{ Close, IfYouCouldJustGoAhead }
import us.theatr.akka.quartz.QuartzActor

import scala.concurrent.duration._

@Singleton
class ShoeboxTasksPlugin @Inject() (
    twitterSyncCommander: TwitterSyncCommander,
    system: ActorSystem,
    quartz: ActorInstance[QuartzActor],
    articleIngestionActor: ActorInstance[ShoeboxArticleIngestionActor],
    messageIngestionActor: ActorInstance[ShoeboxMessageIngestionActor],
    slackChannelDigestActor: ActorInstance[SlackChannelDigestNotificationActor],
    slackTeamDigestActor: ActorInstance[SlackTeamDigestNotificationActor],
    slackIngestingActor: ActorInstance[SlackIngestingActor],
    planRenewalCommander: PlanRenewalCommander,
    paymentProcessingCommander: PaymentProcessingCommander,
    slackIntegrationCommander: SlackIntegrationCommander,
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

    scheduleTaskOnLeader(system, 30 minutes, 30 minutes, "payments processing") {
      planRenewalCommander.processDueRenewals()
      paymentProcessingCommander.processDuePayments()
    }

    scheduleTaskOnLeader(articleIngestionActor.system, 3 minutes, 1 minute, articleIngestionActor.ref, ShoeboxArticleIngestionActor.StartIngestion)
    scheduleTaskOnLeader(messageIngestionActor.system, 500 seconds, 3 minute, messageIngestionActor.ref, IfYouCouldJustGoAhead)
    scheduleTaskOnLeader(slackIngestingActor.system, 3 minute, 20 seconds, slackIngestingActor.ref, IfYouCouldJustGoAhead)

    scheduleTaskOnLeader(slackTeamDigestActor.system, 3 minute, 5 minutes, slackTeamDigestActor.ref, IfYouCouldJustGoAhead)
    // TODO(ryan): if we ever want to push slack channel digests, you can uncomment this line
    // scheduleTaskOnLeader(slackChannelDigestActor.system, 3 minute, 5 minutes, slackChannelDigestActor.ref, IfYouCouldJustGoAhead)
  }

  override def onStop() {
    Seq(messageIngestionActor, slackIngestingActor).foreach(_.ref ! Close)
    super.onStop()
  }

}
