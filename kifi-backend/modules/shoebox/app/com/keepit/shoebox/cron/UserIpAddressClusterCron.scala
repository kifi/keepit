package com.keepit.shoebox.cron

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.{ UserIpAddressCommander, UserIpAddressEventLogger }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.time._
import org.joda.time.Period
import us.theatr.akka.quartz.QuartzActor

import scala.concurrent.ExecutionContext

case class SearchForClusters(timePeriod: Period, numClusters: Int)

trait UserIpAddressClusterCronPlugin extends SchedulerPlugin

@Singleton
class UserIpAddressClusterCronPluginImpl @Inject() (
    actor: ActorInstance[UserIpAddressClusterActor],
    quartz: ActorInstance[QuartzActor],
    val scheduling: SchedulingProperties) extends UserIpAddressClusterCronPlugin with Logging {

  override def enabled: Boolean = true
  override def onStart() {
    val everyWeek = "0 0 0 * * SUN"
    cronTaskOnLeader(quartz, actor.ref, everyWeek, SearchForClusters(Period.weeks(1), numClusters = 50))
  }
}

class UserIpAddressClusterActor @Inject() (
    userIpAddressCommander: UserIpAddressCommander,
    userIpAddressEventLogger: UserIpAddressEventLogger,
    implicit val defaultContext: ExecutionContext,
    airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  def receive = {
    case SearchForClusters(timePeriod, numClusters) =>
      log.info("[IP CRON]: Searching over the past " + timePeriod + " for the largest " + numClusters + " clusters")
      val timeCutoff = currentDateTime.minus(timePeriod)
      val clusterIps = userIpAddressCommander.findIpClustersSince(time = timeCutoff, limit = numClusters)
      clusterIps foreach { ip =>
        val usersAtCluster = userIpAddressCommander.getUsersByIpAddressSince(ip, time = timeCutoff)
        userIpAddressEventLogger.notifySlackChannelAboutCluster(clusterIp = ip, clusterMembers = usersAtCluster.toSet)
      }
  }
}
