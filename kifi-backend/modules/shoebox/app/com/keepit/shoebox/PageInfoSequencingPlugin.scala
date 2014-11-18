package com.keepit.shoebox

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.DbSequenceAssigner
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{ SchedulingProperties, SequencingActor, SequencingPlugin }
import com.keepit.model.{ PageInfo, PageInfoRepo }

trait PageInfoSequencingPlugin extends SequencingPlugin

class PageInfoSequencingPluginImpl @Inject() (
    override val actor: ActorInstance[PageInfoSequencingActor],
    override val scheduling: SchedulingProperties) extends PageInfoSequencingPlugin {
}

@Singleton
class PageInfoSequenceNumberAssigner @Inject() (db: Database, repo: PageInfoRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[PageInfo](db, repo, airbrake)

class PageInfoSequencingActor @Inject() (
  assigner: PageInfoSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
