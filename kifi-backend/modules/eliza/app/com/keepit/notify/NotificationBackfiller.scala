package com.keepit.notify

import akka.actor.Actor.Receive
import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, HttpClient }
import com.keepit.common.plugin.{ SchedulingProperties, SchedulerPlugin }
import com.keepit.eliza.commanders.NotificationCommander
import com.keepit.eliza.model.{ UserThread, UserThreadRepo }
import com.keepit.inject.AppScoped
import com.keepit.model.{ NormalizedURI, User }
import com.keepit.notify.NotificationBackfiller.BackfillNotifications
import play.api.{ Application, Play }
import play.api.libs.json.{ JsValue, Json }
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.Random

@ImplementedBy(classOf[NotificationBackfillerPluginImpl])
trait NotificationBackfillerPlugin extends SchedulerPlugin {

}

@AppScoped
class NotificationBackfillerPluginImpl @Inject() (
    actor: ActorInstance[NotificationBackfiller],
    implicit val application: Application,
    override val scheduling: SchedulingProperties) extends NotificationBackfillerPlugin {

  override def enabled: Boolean = true
  override def onStart() {
    val (initDelay, freq) = if (Play.isDev) (15 seconds, 15 seconds) else (2 minutes, 0.5 minutes)
    log.info(s"[onStart] NotificationBackfillerPlugin started with initDelay=$initDelay freq=$freq")
    scheduleTaskOnOneMachine(actor.system, initDelay, freq, actor.ref, BackfillNotifications, BackfillNotifications.getClass.getName)
  }

}

class NotificationBackfiller @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    httpClient: HttpClient,
    userThreadRepo: UserThreadRepo,
    notificationCommander: NotificationCommander,
    implicit val ec: ExecutionContext) extends FortyTwoActor(airbrake) with Logging {

  def backfillHead(seq: Seq[(Id[UserThread], Id[User], JsValue, Boolean, Option[Id[NormalizedURI]])]): Unit =
    if (seq.nonEmpty) {
      context.system.scheduler.scheduleOnce(0.1 seconds) {
        backfillHead(seq.tail)
      }

      seq.head match {
        case (userThreadId, userId, lastNotif, pending, uriId) =>
          val rawNotif = (userThreadId, lastNotif, pending, uriId)
          val backfilled = notificationCommander.backfillLegacyNotificationsFor(userId, Seq(rawNotif))
          val backfilledIds = backfilled.map(_.notification.id.get).mkString(":")
          log.info(s"Just backfilled $userThreadId -> $backfilledIds")
      }
    }

  override def receive: Receive = {
    case BackfillNotifications =>
      log.info(s"Running backfilling query")
      val needBackfilling = db.readOnlyMaster { implicit session =>
        userThreadRepo.getThreadsThatNeedBackfilling()
      }
      val actuallyBackfill = Random.shuffle(needBackfilling).take(250)
      log.info(s"Need backfilling: ${actuallyBackfill.map(_._1.id).mkString(", ")}")
      backfillHead(actuallyBackfill)
  }
}

object NotificationBackfiller {

  case object BackfillNotifications

}
