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
    system: ActorSystem,
    actor: ActorInstance[EngagementEmailActor],
    quartz: ActorInstance[QuartzActor],
    val scheduling: SchedulingProperties) extends SchedulerPlugin {

  override def onStart() {
    scheduleTaskOnLeader(system, 1 minutes, 5 minutes) {
      ingestionCommander.ingestAll()
    }
    scheduleTaskOnLeader(system, 1 minutes, 5 minutes) {
      generationCommander.precomputeRecommendations()
    }

    scheduleRecommendationEmail()
  }

  private def scheduleRecommendationEmail(): Unit = {
    cronTaskOnLeader(quartz, actor.ref, "0 0 6 * * ?", EngagementEmailTypes.FEED)
  }
}
