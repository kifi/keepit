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

object CuratorTasks {
  val completeDataIngestion = "complete data ingestion"
  val uriRecommendationPrecomputation = "uri recommendation precomputation"
  val uriRecommendationReaper = "uri recommendation reaper"
  val publicFeedReaper = "public feed reaper"
  val libraryRecommendationPrecomputation = "library recommendation precomputation"
  val libraryRecommendationReaper = "library recommendation reaper"
}

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

  import CuratorTasks._

  override def onStart() {
    log.info("CuratorTasksPlugin onStart")
    scheduleTaskOnLeader(system, 15 minutes, 70 minutes, completeDataIngestion) {
      ingestionCommander.ingestAll()
    }
    scheduleTaskOnOneMachine(system, 25 minutes, 71 minutes, uriRecommendationPrecomputation) {
      uriRecoGenerationCommander.precomputeRecommendations()
    }
    scheduleTaskOnOneMachine(system, 45 minutes, 72 minutes, uriRecommendationReaper) {
      uriRecoCleanupCommander.cleanup()
    }

    scheduleTaskOnOneMachine(system, 2 hours, 5 hours, publicFeedReaper) {
      feedCommander.cleanup()
    }

    scheduleTaskOnOneMachine(system, 35 minutes, 73 minutes, libraryRecommendationPrecomputation) {
      libraryRecoGenerationCommander.precomputeRecommendations()
    }
    scheduleTaskOnOneMachine(system, 55 minutes, 74 minutes, libraryRecommendationReaper) {
      libraryRecoCleanupCommander.cleanupLowMasterScoreRecos()
    }

    scheduleTaskOnOneMachine(system, 10 minutes, 10 minutes, emailActor.ref, FeedDigestMessage.Send, FeedDigestMessage.Send.getClass.getSimpleName)
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
    val cronTimeWeekend = s"0 0 $utcHourFor9amEasternTime ? * 7" // 1pm UTC - send every Tuesday at 9am EDT / 6am PDT
    cronTaskOnLeader(quartz, emailActor.ref, cronTime, FeedDigestMessage.Queue)
    //cronTaskOnLeader(quartz, emailActor.ref, cronTimeWeekend, FeedDigestMessage.Queue)
  }
}
