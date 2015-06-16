package com.keepit.shoebox.cron

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.UserIpAddressCommander
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.time._
import org.joda.time.Period
import play.api.libs.json.{ JsValue, Json, Writes }
import us.theatr.akka.quartz.QuartzActor

import scala.concurrent.ExecutionContext

case class SearchForClusters(timePeriod: Period, numClusters: Int)
case class BasicSlackMessage(
  text: String,
  channel: Option[String] = None,
  username: String = "kifi-bot",
  iconUrl: String = "https://djty7jcqog9qu.cloudfront.net/assets/black/logo.png")

object BasicSlackMessage {
  implicit val writes = new Writes[BasicSlackMessage] {
    def writes(o: BasicSlackMessage): JsValue = Json.obj("text" -> o.text, "channel" -> o.channel, "username" -> o.username, "icon_url" -> o.iconUrl)
  }
}

trait UserIpAddressClusterCronPlugin extends SchedulerPlugin

@Singleton
class UserIpAddressClusterCronPluginImpl @Inject() (
    actor: ActorInstance[UserIpAddressClusterActor],
    quartz: ActorInstance[QuartzActor],
    val scheduling: SchedulingProperties) extends UserIpAddressClusterCronPlugin with Logging {

  override def enabled: Boolean = true
  override def onStart() {
    val everyDay = "0 0 0 * * ?"
    cronTaskOnLeader(quartz, actor.ref, everyDay, SearchForClusters(Period.days(1), numClusters = 10))

    val everyWeek = "0 0 0 * * SUN"
    cronTaskOnLeader(quartz, actor.ref, everyWeek, SearchForClusters(Period.weeks(1), numClusters = 50))
  }
}

class UserIpAddressClusterActor @Inject() (
    userIpAddressCommander: UserIpAddressCommander,
    implicit val defaultContext: ExecutionContext,
    airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  def receive = {
    case SearchForClusters(timePeriod, numClusters) =>
      log.info("[IP CRON]: Searching over the past " + timePeriod + " for the largest " + numClusters + " clusters")
      val clusterIps = userIpAddressCommander.findIpClustersSince(time = currentDateTime.minus(timePeriod), limit = numClusters)
      clusterIps foreach {
        userIpAddressCommander.notifySlackChannelAboutCluster(_)
      }
  }
}
