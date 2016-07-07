package com.keepit.shoebox

import akka.actor.ActorSystem
import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.{ TwitterSyncCommander, TwitterWaitlistCommander }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.export.FullExportProcessingActor
import com.keepit.normalizer.NormalizationUpdatingActor
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
    twitterWaitListCommander: TwitterWaitlistCommander,
    system: ActorSystem,
    quartz: ActorInstance[QuartzActor],
    articleIngestionActor: ActorInstance[ShoeboxArticleIngestionActor],
    messageIngestionActor: ActorInstance[ShoeboxMessageIngestionActor],
    slackTeamDigestActor: ActorInstance[SlackTeamDigestNotificationActor],
    slackPersonalDigestActor: ActorInstance[SlackPersonalDigestNotificationActor],
    slackPushingActor: ActorInstance[SlackPushingActor],
    slackIngestingActor: ActorInstance[SlackIngestingActor],
    slackKeepAttributionActor: ActorInstance[SlackKeepAttributionActor],
    normalizationUpdatingActor: ActorInstance[NormalizationUpdatingActor],
    exportingActor: ActorInstance[FullExportProcessingActor],
    planRenewalCommander: PlanRenewalCommander,
    paymentProcessingCommander: PaymentProcessingCommander,
    val scheduling: SchedulingProperties) extends SchedulerPlugin {

  override def onStart() {
    log.info("ShoeboxTasksPlugin onStart")
    scheduleTaskOnOneMachine(system, 3 minute, 1 minutes, "twitter sync") {
      twitterSyncCommander.syncAll()
    }

    scheduleTaskOnLeader(system, 66 seconds, 13 seconds, "twitter waitlist accept") {
      twitterWaitListCommander.processQueue()
    }

    scheduleTaskOnLeader(system, 30 minutes, 30 minutes, "payments processing") {
      planRenewalCommander.processDueRenewals()
      paymentProcessingCommander.processDuePayments()
    }

    scheduleTaskOnLeader(articleIngestionActor.system, 3 minutes, 1 minute, articleIngestionActor.ref, ShoeboxArticleIngestionActor.StartIngestion)
    scheduleTaskOnLeader(messageIngestionActor.system, 500 seconds, 3 minute, messageIngestionActor.ref, IfYouCouldJustGoAhead)
    scheduleTaskOnLeader(slackPushingActor.system, 3 minute, 20 seconds, slackPushingActor.ref, IfYouCouldJustGoAhead)
    scheduleTaskOnLeader(slackIngestingActor.system, 3 minute, 20 seconds, slackIngestingActor.ref, IfYouCouldJustGoAhead)
    scheduleTaskOnLeader(slackKeepAttributionActor.system, 1 minute, 30 minutes, slackKeepAttributionActor.ref, IfYouCouldJustGoAhead)
    scheduleTaskOnLeader(slackTeamDigestActor.system, 3 minute, 5 minutes, slackTeamDigestActor.ref, IfYouCouldJustGoAhead)
    scheduleTaskOnLeader(slackPersonalDigestActor.system, 3 minute, 1 minute, slackPersonalDigestActor.ref, IfYouCouldJustGoAhead)

    scheduleTaskOnAllMachines(exportingActor.system, 3 minute, 1 minute, exportingActor.ref, IfYouCouldJustGoAhead)
    scheduleTaskOnAllMachines(normalizationUpdatingActor.system, 3 minute, 1 minute, normalizationUpdatingActor.ref, IfYouCouldJustGoAhead)
  }

  override def onStop() {
    Seq(
      messageIngestionActor,
      slackIngestingActor,
      slackKeepAttributionActor,
      slackTeamDigestActor,
      slackPersonalDigestActor,
      normalizationUpdatingActor,
      exportingActor
    ).foreach(_.ref ! Close)
    super.onStop()
  }

}
