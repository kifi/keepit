package com.keepit.cortex.models.lda

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulingProperties, SchedulerPlugin }
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel.{ LDAInfoRepo, URILDATopicRepo }
import com.keepit.common.time._
import scala.concurrent.duration._

class LDAInfoUpdateActor @Inject() (updater: LDAInfoUpdater, airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) {
  import LDAInfoUpdateActor._

  def receive = {
    case UpdateTopicSizeCounts => updater.updateTopicSizeCounts()
    case m => throw new UnsupportedActorMessage(m)
  }
}

object LDAInfoUpdateActor {
  case object UpdateTopicSizeCounts
}

trait LDAInfoUpdatePlugin extends SchedulerPlugin

class LDAInfoUpdatePluginImpl @Inject() (
    actor: ActorInstance[LDAInfoUpdateActor],
    val scheduling: SchedulingProperties) extends SchedulerPlugin with LDAInfoUpdatePlugin {

  import LDAInfoUpdateActor._

  override def enabled: Boolean = true

  override def onStart() {
    scheduleTaskOnOneMachine(actor.system, 5 minutes, 24 hours, actor.ref, UpdateTopicSizeCounts, UpdateTopicSizeCounts.getClass.getSimpleName)
  }
}

@Singleton
class LDAInfoUpdater @Inject() (
    db: Database,
    representer: MultiVersionedLDAURIRepresenter,
    uriLDARepo: URILDATopicRepo,
    infoRepo: LDAInfoRepo) extends Logging {

  def updateTopicSizeCounts() {
    representer.versions.foreach { version => updateTopicSizeCounts(version) }
  }

  def updateTopicSizeCounts(version: ModelVersion[DenseLDA]) {
    log.info("updating topic size counts")
    val counts = db.readOnlyReplica { implicit s => uriLDARepo.getTopicCounts(version) }

    db.readWrite { implicit s =>
      counts.foreach {
        case (topicId, count) =>
          val model = infoRepo.getByTopicId(version, topicId)
          infoRepo.save(model.copy(numOfDocs = count).withUpdateTime(currentDateTime))
      }
    }
    log.info("updated topic size counts")
  }
}
