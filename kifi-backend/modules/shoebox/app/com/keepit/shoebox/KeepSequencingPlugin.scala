package com.keepit.shoebox

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.DbSequenceAssigner
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{ SequencingPlugin, SequencingActor, SchedulingProperties }
import com.keepit.model.{ Keep, KeepRepo }

trait KeepSequencingPlugin extends SequencingPlugin

class KeepSequencingPluginImpl @Inject() (
  override val actor: ActorInstance[KeepSequencingActor],
  override val scheduling: SchedulingProperties) extends KeepSequencingPlugin {}

@Singleton
class KeepSequenceNumberAssigner @Inject() (db: Database, repo: KeepRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[Keep](db, repo, airbrake)

class KeepSequencingActor @Inject() (
  assigner: KeepSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
