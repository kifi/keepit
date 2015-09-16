package com.keepit.notify

import akka.actor.Actor.Receive
import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{ DirectUrl, HttpClient }
import com.keepit.common.plugin.{ SchedulingProperties, SchedulerPlugin }
import com.keepit.eliza.commanders.NotificationCommander
import com.keepit.eliza.model.UserThreadRepo
import com.keepit.inject.AppScoped
import com.keepit.notify.NotificationBackfiller.BackfillNotifications
import play.api.{ Application, Play }
import play.api.libs.json.Json
import scala.concurrent.duration._

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
    val (initDelay, freq) = if (Play.isDev) (15 seconds, 15 seconds) else (5 minutes, 3 minutes)
    log.info(s"[onStart] NotificationBackfillerPlugin started with initDelay=$initDelay freq=$freq")
    scheduleTaskOnOneMachine(actor.system, initDelay, freq, actor.ref, BackfillNotifications, BackfillNotifications.getClass.getName)
  }

}

class NotificationBackfiller @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    httpClient: HttpClient,
    userThreadRepo: UserThreadRepo,
    notificationCommander: NotificationCommander) extends FortyTwoActor(airbrake) {
  override def receive: Receive = {
    case BackfillNotifications =>
      val needBackfilling = db.readOnlyMaster { implicit session =>
        userThreadRepo.getThreadsThatNeedBackfilling()
      }
      val msgStr = needBackfilling.map {
        case (userThreadId, userId, lastNotif, pending, uriId) =>
          val rawNotif = (lastNotif, pending, uriId)
          val backfilled = notificationCommander.backfillLegacyNotificationsFor(userId, Seq(rawNotif))
          (userThreadId, backfilled.map(_.notification.id.get).mkString(":"))
      }.map {
        case (userThreadId, backfilledId) => s"$userThreadId -> $backfilledId"
      }.mkString("backfilled: ", ", ", "")
      httpClient.post(DirectUrl("https://hooks.slack.com/services/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"), Json.obj(
        "text" -> msgStr
      ))
  }
}

object NotificationBackfiller {

  case object BackfillNotifications

}
