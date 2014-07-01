package com.keepit.shoebox

import com.google.inject.{Singleton, Inject}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.DbSequenceAssigner
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{SequencingActor, SequencingPlugin, SchedulingProperties}
import com.keepit.model._
import scala.concurrent.duration._

trait UserConnectionSequencingPlugin extends SequencingPlugin

class UserConnectionSequencingPluginImpl @Inject() (
  override val actor: ActorInstance[UserConnectionSequencingActor],
  override val scheduling: SchedulingProperties
) extends UserConnectionSequencingPlugin {

  override val interval: FiniteDuration = 20 seconds
}

@Singleton
class UserConnectionSequenceNumberAssigner @Inject() (db: Database, repo: UserConnectionRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[UserConnection](db, repo, airbrake)

class UserConnectionSequencingActor @Inject() (
  assigner: UserConnectionSequenceNumberAssigner,
  airbrake: AirbrakeNotifier
) extends SequencingActor(assigner, airbrake)
