package com.keepit.rover.tagcloud

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ NamedStatsdTimer, Logging }
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.kifi.franz.SQSQueue
import com.keepit.common.queue.messages.{ SuggestedSearchTermsWithLibraryId, LibrarySuggestedSearchRequest }

import scala.concurrent.Future
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@ImplementedBy(classOf[TagCloudPluginImpl])
trait TagCloudPlugin

@Singleton
class TagCloudPluginImpl @Inject() (
    actor: ActorInstance[TagCloudActor],
    val scheduling: SchedulingProperties) extends SchedulerPlugin with TagCloudPlugin {

  import TagCloudActor.TagCloudActorMessages._

  override def enabled: Boolean = true

  val name: String = getClass.toString

  override def onStart() {
    scheduleTaskOnOneMachine(actor.system, 5 minutes, 2 minutes, actor.ref, Pull, this.getClass.getSimpleName + Pull.getClass.getSimpleName)
  }
}

class TagCloudActor @Inject() (
    updater: TagCloudUpdater,
    airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) {

  import TagCloudActor.TagCloudActorMessages._

  private val lock = new ReactiveLock(1)

  def receive = {
    case Pull => lock.withLockFuture {
      updater.update().map { n =>
        if (n > 0) context.system.scheduler.scheduleOnce(1 seconds, self, Pull)
      }
    }
  }
}

object TagCloudActor {
  trait TagCloudActorMessage
  object TagCloudActorMessages {
    case object Pull extends TagCloudActorMessage
  }
}

@Singleton
class TagCloudUpdater @Inject() (
    requestQueue: SQSQueue[LibrarySuggestedSearchRequest],
    resultQueue: SQSQueue[SuggestedSearchTermsWithLibraryId],
    tagCloudCommander: TagCloudCommander) extends Logging {

  def update(): Future[Int] = {
    requestQueue.next.flatMap { msgOpt =>
      msgOpt match {
        case Some(msg) =>
          val libId = msg.body.id
          val timer = new NamedStatsdTimer("TagCloudUpdater.update")
          log.info(s"processing tag cloud request for library ${libId}")

          tagCloudCommander.generateTagCloud(libId).map { res =>
            log.info(s"done with tag cloud computation for library ${libId}")
            timer.stopAndReport(scaling = 1000.0) // milliseconds to seconds

            resultQueue.send(res)
            msg.consume()
            1
          }

        case None => Future.successful(0)
      }
    }
  }

}
