package com.keepit.common.social

import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.model._
import akka.util.Timeout
import akka.actor._
import com.google.inject.Inject
import com.keepit.common.db.slick._
import scala.concurrent.duration._
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.plugin._
import com.keepit.common.actor.ActorFactory
import play.api.Plugin
import com.keepit.social.SocialGraphPlugin

private case class RefreshUserInfo(socialUserInfo: SocialUserInfo)
private case object RefreshAll

private[social] class SocialGraphRefresherActor @Inject() (
    healthcheckPlugin: HealthcheckPlugin,
    socialGraphPlugin : SocialGraphPlugin,
    db: Database,
    socialRepo: SocialUserInfoRepo)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case RefreshAll => {
      log.info("going to check which SocilaUserInfo Was not fetched Lately")
      val needToBeRefreshed = db.readOnly { implicit s => socialRepo.getNeedToBeRefreshed }
      log.info("find %s users that need to be refreshed".format(needToBeRefreshed.size))
      needToBeRefreshed.foreach(self ! RefreshUserInfo(_))
    }
    case RefreshUserInfo(userInfo) => {
      log.info("found socialUserInfo that need to be refreshed %s".format(userInfo))
      socialGraphPlugin.asyncFetch(userInfo)
    }
    case m => throw new Exception("unknown message %s".format(m))
  }
}


trait SocialGraphRefresher extends Plugin {}

class SocialGraphRefresherImpl @Inject() (
    actorFactory: ActorFactory[SocialGraphRefresherActor],
    val schedulingProperties: SchedulingProperties) //only on leader
  extends SocialGraphRefresher with SchedulingPlugin {

  implicit val actorTimeout = Timeout(5 seconds)

  private lazy val actor = actorFactory.actor

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    scheduleTask(actorFactory.system, 90 seconds, 5 minutes, actor, RefreshAll)
  }
}
