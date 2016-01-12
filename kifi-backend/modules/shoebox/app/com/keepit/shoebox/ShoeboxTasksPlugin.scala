package com.keepit.shoebox

import akka.actor.ActorSystem
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.shoebox.eliza.ShoeboxMessageIngestionActor
import com.keepit.shoebox.rover.ShoeboxArticleIngestionActor
import com.keepit.slack.{ SlackTeamCommander, SlackCommander, LibraryToSlackChannelPusher, SlackIngestionCommander }
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
    planRenewalCommander: PlanRenewalCommander,
    paymentProcessingCommander: PaymentProcessingCommander,
    slackCommander: SlackCommander,
    slackTeamCommander: SlackTeamCommander,
    slackIngestionCommander: SlackIngestionCommander,
    libToSlackPusher: LibraryToSlackChannelPusher,
    val scheduling: SchedulingProperties) extends SchedulerPlugin {

  override def onStart() {
    log.info("ShoeboxTasksPlugin onStart")
    scheduleTaskOnOneMachine(system, 3 minute, 1 minutes, "twitter sync") {
      twitterSyncCommander.syncAll()
    }

    scheduleTaskOnLeader(system, 3 minute, 20 seconds, "slack ingestion") {
      slackIngestionCommander.ingestAllDue()
    }

    scheduleTaskOnLeader(system, 1 minute, 30 seconds, "fetching missing Slack channel ids") {
      slackCommander.fetchMissingChannelIds()
    }

    scheduleTaskOnOneMachine(system, 1 minute, 20 seconds, "slack pushing") {
      libToSlackPusher.findAndPushUpdatesForRipestLibraries()
    }

    // TODO(ryan): make these way slower, no need to run this that often
    scheduleTaskOnOneMachine(system, 5 minutes, 1 minute, "slack digests") {
      slackTeamCommander.pushDigestNotificationsForRipeTeams()
    }

    scheduleTaskOnLeader(system, 30 minutes, 30 minutes, "payments processing") {
      planRenewalCommander.processDueRenewals()
      paymentProcessingCommander.processDuePayments()
    }

    scheduleTaskOnLeader(articleIngestionActor.system, 3 minutes, 1 minute, articleIngestionActor.ref, ShoeboxArticleIngestionActor.StartIngestion)
    scheduleTaskOnLeader(messageIngestionActor.system, 500 seconds, 3 minute, messageIngestionActor.ref, IfYouCouldJustGoAhead)
  }

  override def onStop() {
    Seq(messageIngestionActor).foreach(_.ref ! Close)
    super.onStop()
  }

}
