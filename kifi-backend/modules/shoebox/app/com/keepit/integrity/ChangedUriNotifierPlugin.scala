package com.keepit.integrity

import scala.concurrent.duration.DurationInt

import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.SchedulingPlugin
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.common.zookeeper.LongCentralConfigKey
import com.keepit.model.ChangedURIRepo
import com.keepit.model.ChangedURIRepoImpl


case class ChangedUriSeqNumKey(val name: String = "changed_uri_seq") extends LongCentralConfigKey {
  val namespace = "changed_uri"
  def key: String = name
}

case object ReportSeq

class ChangedUriNotifierActor @Inject() (
  db: Database,
  changedUriRepo: ChangedURIRepo,
  centralConfig: CentralConfig,
  healthcheckPlugin: HealthcheckPlugin
) extends FortyTwoActor(healthcheckPlugin) with Logging {
  def receive() = {
    case ReportSeq => {
      val seq = db.readOnly { implicit s =>
        changedUriRepo.getHighestSeqNum
      }
      if (seq.isDefined) {
        log.info(s"update changed_uri_seq to ${seq.get.value}")
        centralConfig.update(new ChangedUriSeqNumKey(), seq.get.value)
      }
    }
  }
}

@ImplementedBy(classOf[ChangedUriNotifierPluginImpl])
trait ChangedUriNotifierPlugin extends SchedulingPlugin

@Singleton
class ChangedUriNotifierPluginImpl @Inject()(
  actor: ActorInstance[ChangedUriNotifierActor],
  val schedulingProperties: SchedulingProperties  //only on leader
) extends ChangedUriNotifierPlugin with Logging {

  override def enabled: Boolean = true
  override def onStart(){
    log.info("starting ChangedUriNotifierPluginImpl")
    scheduleTask(actor.system, 1 minutes, 2 minutes, actor.ref, ReportSeq)
  }

  override def onStop() {
    log.info("stopping ChangedUriNotifierPluginImpl")
    cancelTasks()
  }
}