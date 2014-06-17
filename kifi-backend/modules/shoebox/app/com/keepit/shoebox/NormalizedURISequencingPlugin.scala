package com.keepit.shoebox

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{DbSequenceAssigner, DbSequencingActor, DbSequencingPlugin, SchedulingProperties}
import com.keepit.model.{NormalizedURIRepo, NormalizedURI}

trait NormalizedURISequencingPlugin extends DbSequencingPlugin[NormalizedURI]

class NormalizedURISequencingPluginImpl @Inject() (
  override val actor: ActorInstance[NormalizedURISequencingActor],
  override val scheduling: SchedulingProperties
) extends NormalizedURISequencingPlugin

class NormalizedURISequenceNumberAssigner @Inject() (
  db: Database,
  repo: NormalizedURIRepo,
  airbrake: AirbrakeNotifier
) extends DbSequenceAssigner[NormalizedURI](airbrake) {

  protected def assignSequenceNumbers(limit: Int): Int = {
    db.readWrite{ implicit session => repo.assignSequenceNumbers(limit) }
  }

}

class NormalizedURISequencingActor @Inject() (
  assigner: NormalizedURISequenceNumberAssigner,
  airbrake: AirbrakeNotifier
) extends DbSequencingActor[NormalizedURI](assigner, airbrake)
