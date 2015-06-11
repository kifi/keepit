package com.keepit.shoebox.cron

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.UserIpAddressCommander
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, HttpClient }
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.service.IpAddress
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.Period
import play.api.libs.json.{ JsValue, Json, Writes }
import us.theatr.akka.quartz.QuartzActor

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }

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
    val every5Minutes = "0 5 0 * * ?"
    cronTaskOnLeader(quartz, actor.ref, every5Minutes, SearchForClusters(Period.minutes(5), 1))

    val everyDay = "0 0 0 * * ?"
    cronTaskOnLeader(quartz, actor.ref, everyDay, SearchForClusters(Period.days(1), 10))

    val everyWeek = "0 0 0 * * SUN"
    cronTaskOnLeader(quartz, actor.ref, everyWeek, SearchForClusters(Period.weeks(1), 100))
  }
}

class UserIpAddressClusterActor @Inject() (
    userIpAddressCommander: UserIpAddressCommander,
    httpClient: HttpClient,
    implicit val defaultContext: ExecutionContext,
    airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {
  private val ipClusterSlackChannelUrl = "https://hooks.slack.com/services/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"

  def formatClusters(clusters: Seq[(IpAddress, Int, Id[User])]): String = {
    val lines = for ((ip, count, userId) <- clusters) yield {
      "Cluster of " + count + " at " + ip + " with user " + userId
    }
    lines.mkString("\n")
  }

  def receive = {
    case SearchForClusters(timePeriod, numClusters) =>
      val clusters = userIpAddressCommander.findIpClustersSince(time = currentDateTime.minus(timePeriod), limit = 10)
      val body = BasicSlackMessage(formatClusters(clusters))
      val request = httpClient.postFuture(DirectUrl(ipClusterSlackChannelUrl), Json.toJson(body))
      request onComplete {
        case Success(response) => log.info("[IP CRON] Success: " + response)
        case Failure(ex) => log.info("[IP CRON] Failure: " + ex)
      }
  }
}
