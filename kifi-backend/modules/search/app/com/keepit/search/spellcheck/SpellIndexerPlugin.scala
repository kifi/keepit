package com.keepit.search.spellcheck

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.healthcheck.{AirbrakeError,AirbrakeNotifier}
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import scala.concurrent.duration._

private[spellcheck] case object BuildDict

private[spellcheck] class SpellIndexerActor @Inject()(
  builder: SpellIndexer,
  airbrake: AirbrakeNotifier
) extends FortyTwoActor(airbrake) with Logging {
  def receive = {
    case BuildDict => builder.buildDictionary
    case m => throw new UnsupportedActorMessage(m)
  }
}

trait SpellIndexerPlugin extends SchedulingPlugin

class SpellIndexerPluginImpl @Inject()(
  actor: ActorInstance[SpellIndexerActor]
) extends SpellIndexerPlugin {

  val schedulingProperties = SchedulingProperties.AlwaysEnabled
  override def enabled: Boolean = true

  override def onStart() {
    scheduleTask(actor.system, 2 minute, 12 hour, actor.ref, BuildDict)
    log.info("starting SpellDictionaryPluginImpl")
  }
  override def onStop() {
    log.info("stopping SpellDictionaryPluginImpl")
    cancelTasks()
  }

}