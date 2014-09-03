package com.keepit.curator.commanders

import akka.actor.ActorSystem
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
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
    // <sec> <min> <hr> <day of mo> <mo> <day of wk> <yr>
    //val cronTime = "0 0 13 ? * TUE" // 1pm UTC == 6am PDT - send every Tuesday at 6am PDT
    val cronTime = "0 0 17 ? * WED" // 5pm UTC == 10am PDT todo(josh) delete this line, uncomment above line
    cronTaskOnLeader(quartz, emailActor.ref, cronTime, EngagementEmailTypes.FEED)
  }
}
