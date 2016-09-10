package com.keepit.eliza

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.eliza.integrity.{ ElizaMessageByMessageIntegrityActor, ElizaMessageThreadByMessageIntegrityActor }
import com.keepit.eliza.shoebox.ElizaKeepIngestingActor
import com.kifi.juggle.ConcurrentTaskProcessingActor.{ Close, IfYouCouldJustGoAhead }

import scala.concurrent.duration._

@Singleton
class ElizaTasksPlugin @Inject() (
  messageThreadByMessageIntegrityActor: ActorInstance[ElizaMessageThreadByMessageIntegrityActor],
  messageByMessageIntegrityActor: ActorInstance[ElizaMessageByMessageIntegrityActor],
  keepIngestingActor: ActorInstance[ElizaKeepIngestingActor],
  val scheduling: SchedulingProperties)
    extends SchedulerPlugin {

  private def taskActors = Seq(
//    keepIngestingActor -> 1.minute,
//    messageThreadByMessageIntegrityActor -> 3.minutes,
//    messageByMessageIntegrityActor -> 1.minutes
  )

  override def onStart() { //keep me alive!
    log.info("ElizaTasksPlugin onStart")
    taskActors.zipWithIndex.foreach {
      case ((actor, freq), idx) =>
        scheduleTaskOnLeader(actor.system, (180 + 10 * idx) seconds, freq, actor.ref, IfYouCouldJustGoAhead)
    }
  }

  override def onStop() {
    taskActors.foreach { case (actor, _) => actor.ref ! Close }
    super.onStop()
  }

}
