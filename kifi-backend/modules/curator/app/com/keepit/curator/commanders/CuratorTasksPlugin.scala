package com.keepit.curator.commanders

import akka.actor.ActorSystem
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.time._
import email.{ EngagementEmailTypes, EngagementEmailActor }
import us.theatr.akka.quartz.QuartzActor

import scala.concurrent.duration._

@Singleton
class CuratorTasksPlugin @Inject() (
    ingestionCommander: SeedIngestionCommander,
    generationCommander: RecommendationGenerationCommander,
    feedCommander: PublicFeedGenerationCommander,
    cleanupCommander: RecommendationCleanupCommander,
    system: ActorSystem,
    emailActor: ActorInstance[EngagementEmailActor],
    quartz: ActorInstance[QuartzActor],
    val scheduling: SchedulingProperties) extends SchedulerPlugin {

  override def onStart() {
    log.info("CuratorTasksPlugin onStart")
    scheduleTaskOnLeader(system, 1 minutes, 5 minutes) {
      ingestionCommander.ingestAll()
    }
    scheduleTaskOnLeader(system, 1 minutes, 2 minutes) {
      generationCommander.precomputeRecommendations()
    }
    scheduleTaskOnLeader(system, 1 minutes, 2 minutes) {
      feedCommander.precomputePublicFeeds()
    }
    scheduleTaskOnLeader(system, 1 hours, 5 hours) {
      cleanupCommander.cleanupLowMasterScoreRecos()
    }
    scheduleTaskOnLeader(system, 1 hours, 10 hours) {
      cleanupCommander.cleanupLowMasterScoreFeeds()
    }

    scheduleRecommendationEmail()
  }

  private def scheduleRecommendationEmail(): Unit = {
    // computes UTC hour for current 9am ET (EDT or EST)
    val nowET = currentDateTime(zones.ET)
    val offsetMillisToUtc = zones.ET.getOffset(nowET)
    val offsetHoursToUtc = offsetMillisToUtc / 1000 / 60 / 60
    val utcHourFor9amEasternTime = 9 + -offsetHoursToUtc

    // <sec> <min> <hr> <day of mo> <mo> <day of wk> <yr>
    val cronTime = s"0 0 $utcHourFor9amEasternTime ? * 3" // 1pm UTC - send every Tuesday at 9am EDT / 6am PDT
    cronTaskOnLeader(quartz, emailActor.ref, cronTime, EngagementEmailTypes.FEED)
  }
}
