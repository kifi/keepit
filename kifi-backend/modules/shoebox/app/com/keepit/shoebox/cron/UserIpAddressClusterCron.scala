package com.keepit.shoebox.cron

import com.google.inject.Inject
import com.keepit.commanders.UserIpAddressCommander
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.service.IpAddress
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.shoebox.cron.UserIpAddressClusterAction.{ DailySearchForClusters, WeeklySearchForClusters }
import us.theatr.akka.quartz.QuartzActor

trait UserIpAddressClusterCronPlugin extends SchedulerPlugin

class UserIpAddressClusterCronPluginImpl @Inject() (
    actor: ActorInstance[GratificationEmailActor],
    quartz: ActorInstance[QuartzActor],
    val scheduling: SchedulingProperties) extends UserIpAddressClusterCronPlugin with Logging {

  override def enabled: Boolean = true
  override def onStart() {
    val cronTimeEveryDay = "0 0 0 * * ?"
    cronTaskOnLeader(quartz, actor.ref, cronTimeEveryDay, UserIpAddressClusterAction.DailySearchForClusters)

    val cronTimeEveryWeek = "0 0 0 * * SUN"
    cronTaskOnLeader(quartz, actor.ref, cronTimeEveryWeek, UserIpAddressClusterAction.WeeklySearchForClusters)
  }
}

object UserIpAddressClusterAction {
  object DailySearchForClusters
  object WeeklySearchForClusters
}

class UserIpAddressClusterActor @Inject() (
    userIpAddressCommander: UserIpAddressCommander,
    airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {
  private val ipClusterSlackChannel = "#useripclusters"

  def formatClusters(clusters: Seq[(IpAddress, Int, Id[User])]): String = {
    val lines = for ((ip, count, userId) <- clusters) yield {
      "Cluster of " + count + " at " + ip + " with user " + userId
    }
    lines.mkString("\n")
  }

  def receive = {
    case DailySearchForClusters =>
      val clusters = userIpAddressCommander.findIpClustersSince(time = currentDateTime.minusDays(1), limit = 10)
      log.info(formatClusters(clusters))
    case WeeklySearchForClusters =>
      val clusters = userIpAddressCommander.findIpClustersSince(time = currentDateTime.minusDays(7), limit = 100)
      log.info(formatClusters(clusters))
  }
}
