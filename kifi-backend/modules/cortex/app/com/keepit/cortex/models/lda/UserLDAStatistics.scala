package com.keepit.cortex.models.lda

import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.time._
import com.keepit.cortex.MiscPrefix
import com.keepit.cortex.core.{ Versionable, ModelVersion }
import com.keepit.cortex.dbmodel.UserLDAInterestsRepo
import com.keepit.cortex.utils.MatrixUtils
import org.joda.time.DateTime
import play.api.libs.json.Json
import scala.concurrent.duration._

// can be used to standarize data to zero mean, unit variance.
case class UserLDAStatistics(
  updatedAt: DateTime,
  version: ModelVersion[DenseLDA],
  mean: Array[Float],
  std: Array[Float],
  min: Array[Float],
  max: Array[Float]) extends Versionable[DenseLDA]

object UserLDAStatistics {
  implicit val format = Json.format[UserLDAStatistics]
}

class UserLDAStatisticsActor @Inject() (airbrake: AirbrakeNotifier, updater: UserLDAStatisticsUpdater) extends FortyTwoActor(airbrake) {
  import UserLDAStatisticsActor._
  def receive = {
    case UpdateUserLDAStatistics => updater.update()
  }
}

object UserLDAStatisticsActor {
  sealed trait UserLDAStatisticsActorMessage
  object UpdateUserLDAStatistics extends UserLDAStatisticsActorMessage
}

trait UserLDAStatisticsPlugin

class UserLDAStatisticsPluginImpl(
    actor: ActorInstance[UserLDAStatisticsActor],
    val scheduling: SchedulingProperties) extends SchedulerPlugin with UserLDAStatisticsPlugin {
  import UserLDAStatisticsActor._

  override def enabled: Boolean = true

  override def onStart() {
    scheduleTaskOnLeader(actor.system, 2 minutes, 12 hours, actor.ref, UpdateUserLDAStatistics)
  }
}

@Singleton
class UserLDAStatisticsUpdater @Inject() (
    db: Database,
    userTopicRepo: UserLDAInterestsRepo,
    representer: LDAURIRepresenter,
    statsStore: UserLDAStatisticsStore) {

  def update() {
    val vecs = db.readOnlyReplica { implicit s => userTopicRepo.all() }.filter { _.numOfEvidence > 20 }.map { _.userTopicMean.get.mean }
    val stats = genStats(vecs)
    statsStore.+=(MiscPrefix.LDA.userLDAStatsJsonFile, representer.version, stats)
  }

  private def genStats(vecs: Seq[Array[Float]]): UserLDAStatistics = {
    val (min, max) = MatrixUtils.getMinAndMax(vecs)
    val (mean, std) = MatrixUtils.getMeanAndStd(vecs)
    UserLDAStatistics(currentDateTime, representer.version, mean, std, min, max)
  }
}
