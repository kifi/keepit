package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.common.actor.ActorFactory
import com.keepit.common.healthcheck.HealthcheckPlugin

import akka.actor._
import scala.concurrent.Await
import scala.concurrent.Future
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.plugin.SchedulingPlugin
import scala.util.{Try, Success, Failure}

private case class FetchUserInfo(socialUserInfo: SocialUserInfo)
private case class FetchUserInfoQuietly(socialUserInfo: SocialUserInfo)
private case object FetchAll

private[social] class SocialGraphActor @Inject() (
    healthcheckPlugin: HealthcheckPlugin,
    graph: FacebookSocialGraph,
    db: Database,
    socialRepo: SocialUserInfoRepo,
    socialUserRawInfoStore: SocialUserRawInfoStore,
    socialUserImportFriends: SocialUserImportFriends,
    socialUserImportEmail: SocialUserImportEmail,
    socialUserCreateConnections: UserConnectionCreator)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case FetchAll =>
      val unprocessedUsers = db.readOnly {implicit s =>
        socialRepo.getUnprocessed()
      }
      unprocessedUsers foreach { user =>
        self ! FetchUserInfoQuietly(user)
      }
      sender ! unprocessedUsers.size

    case FetchUserInfo(socialUserInfo) =>
      sender ! fetchUserInfo(socialUserInfo)

    case FetchUserInfoQuietly(socialUserInfo) =>
      fetchUserInfo(socialUserInfo)

    case m => throw new Exception("unknown message %s".format(m))
  }

  def fetchUserInfo(socialUserInfo: SocialUserInfo): Seq[SocialConnection] = {
      try {
        require(socialUserInfo.credentials.isDefined,
          "social user info's credentials are not defined: %s".format(socialUserInfo))
        val rawInfo = graph.fetchSocialUserRawInfo(socialUserInfo)

        socialUserImportEmail.importEmail(socialUserInfo.userId.get, rawInfo.jsons)
        socialUserRawInfoStore += (socialUserInfo.id.get -> rawInfo)

        socialUserImportFriends.importFriends(rawInfo.jsons)
        val connections = socialUserCreateConnections.createConnections(socialUserInfo, rawInfo.jsons)

        db.readWrite { implicit c =>
          socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.FETCHED_USING_SELF).withLastGraphRefresh())
        }
        connections
      } catch {
        //todo(yonatan): healthcheck event, granular exception catching, frontend should be notified.
        case ex: Exception =>
          db.readWrite { implicit c =>
            socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.FETCH_FAIL).withLastGraphRefresh())
          }
          throw new Exception(s"Problem Fetching User Info for $socialUserInfo", ex)
      }
  }
}

trait SocialGraphPlugin extends SchedulingPlugin {
  def asyncFetch(socialUserInfo: SocialUserInfo): Future[Seq[SocialConnection]]
}

class SocialGraphPluginImpl @Inject() (
    actorFactory: ActorFactory[SocialGraphActor])
  extends SocialGraphPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  private lazy val actor = actorFactory.get()

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting SocialGraphPluginImpl")
    scheduleTask(actorFactory.system, 10 seconds, 1 minutes, actor, FetchAll)
  }
  override def onStop() {
    log.info("stopping SocialGraphPluginImpl")
  }

  override def asyncFetch(socialUserInfo: SocialUserInfo): Future[Seq[SocialConnection]] = {
    require(socialUserInfo.credentials.isDefined,
      "social user info's credentials are not defined: %s".format(socialUserInfo))
    actor.ask(FetchUserInfo(socialUserInfo))(5 minutes).mapTo[Seq[SocialConnection]]
  }
}



