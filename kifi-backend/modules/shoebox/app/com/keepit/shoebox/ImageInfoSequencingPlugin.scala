package com.keepit.shoebox

import com.google.inject.{Singleton, Inject}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.DbSequenceAssigner
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{SequencingActor, SequencingPlugin, SchedulingProperties}
import com.keepit.model._
import scala.concurrent.duration._

import scala.concurrent.duration.FiniteDuration

trait ImageInfoSequencingPlugin extends SequencingPlugin

class ImageInfoSequencingPluginImpl @Inject() (
  override val actor: ActorInstance[ImageInfoSequencingActor],
  override val scheduling: SchedulingProperties
) extends ImageInfoSequencingPlugin {

  override val interval: FiniteDuration = 5 seconds
}

@Singleton
class ImageInfoSequenceNumberAssigner @Inject() (db: Database, repo: ImageInfoRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[ImageInfo](db, repo, airbrake)

class ImageInfoSequencingActor @Inject() (
  assigner: ImageInfoSequenceNumberAssigner,
  airbrake: AirbrakeNotifier
) extends SequencingActor(assigner, airbrake)
