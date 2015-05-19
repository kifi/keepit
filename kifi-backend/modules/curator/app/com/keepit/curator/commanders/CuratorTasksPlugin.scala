package com.keepit.curator.commanders

import akka.actor.ActorSystem
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.time._
import com.keepit.curator.LibraryRecommendationCleanupCommander
import com.keepit.curator.model.RawSeedItemSequenceNumberAssigner
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
  val rawSeedItemSeqNumChecks = "rawSeedItem sequence number sanity check"
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
    rawSeedSeqNumAssigner: RawSeedItemSequenceNumberAssigner,
    val scheduling: SchedulingProperties) extends SchedulerPlugin {

  import CuratorTasks._

  override def onStart() {
    log.info("CuratorTasksPlugin onStart")
    // scheduleTaskOnLeader(system, 5 minutes, 3 minutes, completeDataIngestion) {
    //   ingestionCommander.ingestAll()
    // }
    scheduleTaskOnLeader(system, 5 minutes, 1 minutes, completeDataIngestion) {
      ingestionCommander.cleanUpRawSeedItems()
    }
    scheduleTaskOnAllMachines(system, 5 minutes, 5 minutes, uriRecommendationPrecomputation) {
      uriRecoGenerationCommander.precomputeRecommendations()
    }
    scheduleTaskOnOneMachine(system, 2 minutes, CuratorTasksPlugin.CLEAN_FREQ minutes, uriRecommendationReaper) {
      uriRecoCleanupCommander.cleanup()
    }

    scheduleTaskOnAllMachines(system, 20 minutes, 120 minutes, rawSeedItemSeqNumChecks) {
      rawSeedSeqNumAssigner.sanityCheck()
    }

    scheduleTaskOnOneMachine(system, 1 hours, 5 hours, publicFeedReaper) {
      feedCommander.cleanup()
    }

    scheduleTaskOnOneMachine(system, 7 minutes, 5 minutes, libraryRecommendationPrecomputation) {
      libraryRecoGenerationCommander.precomputeRecommendations()
    }
    scheduleTaskOnOneMachine(system, 10 minutes, 10 minutes, libraryRecommendationReaper) {
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

object CuratorTasksPlugin {
  val CLEAN_FREQ = 2 // minutes
}
