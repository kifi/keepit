package com.keepit.curator.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.logging.Logging
import com.keepit.model.EmailAccountUpdate
import com.keepit.common.db.slick.Database
import scala.util.{ Failure, Success }
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import akka.actor.ActorSystem

@Singleton
class SeedIngestionPlugin @Inject() (
    commander: SeedIngestionCommander,
    system: ActorSystem,
    val scheduling: SchedulingProperties) extends SchedulerPlugin {

  override def onStart() {
    scheduleTaskOnLeader(system, 1 minutes, 1 minutes) {
      commander.ingestAll()
    }
  }
}
