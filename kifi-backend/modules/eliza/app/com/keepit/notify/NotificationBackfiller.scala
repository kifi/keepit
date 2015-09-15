package com.keepit.notify

import akka.actor.Actor.Receive
import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{SchedulingProperties, SchedulerPlugin}
import com.keepit.eliza.commanders.NotificationCommander
import com.keepit.eliza.model.UserThreadRepo
import com.keepit.notify.NotificationBackfiller.BackfillNotifications
import play.api.Play
import scala.concurrent.duration._

@ImplementedBy[NotificationBackfillerPluginImpl]
trait NotificationBackfillerPlugin extends SchedulerPlugin {


}

class NotificationBackfillerPluginImpl @Inject() (
  actor: ActorInstance[NotificationBackfiller],
  override val scheduling: SchedulingProperties
) extends NotificationBackfillerPlugin {

  override def onStart() {
    for (app <- Play.maybeApplication) {
      val (initDelay, freq) = if (Play.isDev) (15 seconds, 15 seconds) else (5 minutes, 3 minutes)
      log.info(s"[onStart] NotificationBackfillerPlugin started with initDelay=$initDelay freq=$freq")
      scheduleTaskOnOneMachine(actor.system, initDelay, freq, actor.ref, BackfillNotifications, BackfillNotifications.getClass.getName)
    }
  }

}

class NotificationBackfiller @Inject() (
  airbrake: AirbrakeNotifier,
  db: Database,
  userThreadRepo: UserThreadRepo,
  notificationCommander: NotificationCommander
) extends FortyTwoActor(airbrake) {
  override def receive: Receive = {
    case BackfillNotifications =>
      val needBackfilling = db.readOnlyMaster { implicit session =>
        
      }
  }
}

object NotificationBackfiller {

  case object BackfillNotifications

}
