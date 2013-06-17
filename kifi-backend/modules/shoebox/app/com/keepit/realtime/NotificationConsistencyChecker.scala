package com.keepit.realtime

import scala.concurrent.duration._

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.actor.ActorFactory
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.model.UserNotificationRepo

import akka.actor.ActorSystem
import play.api.Plugin

sealed abstract class NotificationConsistencyMessage
private case object VerifyVisited extends NotificationConsistencyMessage

private[realtime] class NotificationConsistencyActor @Inject() (
    db: Database,
    notificationRepo: UserNotificationRepo,
    healthcheckPlugin: HealthcheckPlugin
  ) extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive = {
    case VerifyVisited =>
      for {
        n <- db.readOnly { implicit s =>
          notificationRepo.getUnvisitedNotificationsWithCommentRead()
        }
      } {
        healthcheckPlugin.addError(HealthcheckError(
          callType = Healthcheck.INTERNAL,
          errorMessage = Some(s"Notification $n is not marked as visited but references a read message")
        ))
      }
  }
}

@ImplementedBy(classOf[NotificationConsistencyCheckerImpl])
trait NotificationConsistencyChecker extends Plugin {
  def verifyVisited()
}

class NotificationConsistencyCheckerImpl @Inject()(
    system: ActorSystem,
    actorFactory: ActorFactory[NotificationConsistencyActor],
    val schedulingProperties: SchedulingProperties)
  extends SchedulingPlugin with NotificationConsistencyChecker {

  private lazy val actor = actorFactory.get()

  def verifyVisited() {
    actor ! VerifyVisited
  }

  override def onStart() {
    scheduleTask(system, 2 minutes, 1 hour, actor, VerifyVisited)
    super.onStart()
  }
}
