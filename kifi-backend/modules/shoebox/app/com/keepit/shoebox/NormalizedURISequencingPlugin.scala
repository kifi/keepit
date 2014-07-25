package com.keepit.shoebox

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.DbSequenceAssigner
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{ SequencingActor, SequencingPlugin, SchedulingProperties }
import com.keepit.model.{ NormalizedURIRepo, NormalizedURI }

trait NormalizedURISequencingPlugin extends SequencingPlugin

class NormalizedURISequencingPluginImpl @Inject() (
  override val actor: ActorInstance[NormalizedURISequencingActor],
  override val scheduling: SchedulingProperties) extends NormalizedURISequencingPlugin

@Singleton
class NormalizedURISequenceNumberAssigner @Inject() (db: Database, repo: NormalizedURIRepo, val airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[NormalizedURI](db, repo, airbrake)

class NormalizedURISequencingActor @Inject() (
  assigner: NormalizedURISequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
