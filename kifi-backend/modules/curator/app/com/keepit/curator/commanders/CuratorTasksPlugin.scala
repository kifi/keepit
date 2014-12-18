package com.keepit.curator.commanders

import akka.actor.ActorSystem
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.time._
import com.keepit.curator.LibraryRecommendationCleanupCommander
import email.{ FeedDigestMessage, EngagementEmailActor }
import us.theatr.akka.quartz.QuartzActor

import scala.concurrent.duration._

@Singleton
class CuratorTasksPlugin @Inject() (
    ingestionCommander: SeedIngestionCommander,
    uriRecoGenerationCommander: RecommendationGenerationCommander,
    libraryRecoGenerationCommander: LibraryRecommendationGenerationCommander,
    feedCommander: PublicFeedGenerationCommander,
    uriRecoCleanupCommander: RecommendationCleanupCommander,
    libraryRecoCleanupCommander: LibraryRecommendationCleanupCommander,
    system: ActorSystem,
    emailActor: ActorInstance[EngagementEmailActor],
    quartz: ActorInstance[QuartzActor],
    val scheduling: SchedulingProperties) extends SchedulerPlugin {

  override def onStart() {
    log.info("CuratorTasksPlugin onStart")
    scheduleTaskOnLeader(system, 5 minutes, 5 minutes, "complete data ingestion") {
      ingestionCommander.ingestAll()
    }
    scheduleTaskOnLeader(system, 3 minutes, 2 minutes, "recommendation precomputation") {
      uriRecoGenerationCommander.precomputeRecommendations()
    }
    scheduleTaskOnLeader(system, 10 minutes, 10 minutes, "recommendation reaper") {
      uriRecoCleanupCommander.cleanupLowMasterScoreRecos()
    }

    scheduleTaskOnLeader(system, 1 hours, 5 hours, "public feed reaper") {
      feedCommander.cleanup()
    }

    scheduleTaskOnLeader(system, 1 minutes, 3 minutes, "library recommendation precomputation") {
      libraryRecoGenerationCommander.precomputeRecommendations()
    }
    scheduleTaskOnLeader(system, 10 minutes, 10 minutes, "library recommendation reaper") {
      libraryRecoCleanupCommander.cleanupLowMasterScoreRecos()
    }

    scheduleTaskOnLeader(system, 10 minutes, 10 minutes, emailActor.ref, FeedDigestMessage.Send)
    scheduleFeedDigestEmails()
  }

  private def scheduleFeedDigestEmails(): Unit = {
    // computes UTC hour for current 9am ET (EDT or EST)
    val nowET = currentDateTime(zones.ET)
    val offsetMillisToUtc = zones.ET.getOffset(nowET)
    val offsetHoursToUtc = offsetMillisToUtc / 1000 / 60 / 60
    val utcHourFor9amEasternTime = 9 + -offsetHoursToUtc

    // <sec> <min> <hr> <day of mo> <mo> <day of wk> <yr>
    val cronTime = s"0 0 $utcHourFor9amEasternTime ? * 3" // 1pm UTC - send every Tuesday at 9am EDT / 6am PDT
    cronTaskOnLeader(quartz, emailActor.ref, cronTime, FeedDigestMessage.Queue)
  }
}
