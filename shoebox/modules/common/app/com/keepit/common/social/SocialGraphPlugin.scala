package com.keepit.common.social

import scala.concurrent.Future
import scala.concurrent.duration._

import com.google.inject.Inject
import com.keepit.common.actor.ActorFactory
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.net.NonOKResponseException
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.model._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import play.api.Plugin

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

        rawInfo.jsons flatMap graph.extractEmails map (socialUserImportEmail.importEmail(socialUserInfo.userId.get, _))

        socialUserRawInfoStore += (socialUserInfo.id.get -> rawInfo)

        val friends = rawInfo.jsons flatMap graph.extractFriends
        socialUserImportFriends.importFriends(friends, SocialNetworks.FACEBOOK)
        val connections = socialUserCreateConnections.createConnections(
          socialUserInfo, friends.map(_._1.socialId), SocialNetworks.FACEBOOK)

        db.readWrite { implicit c =>
          socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.FETCHED_USING_SELF).withLastGraphRefresh())
        }
        connections
      } catch {
        case e @ NonOKResponseException(url, response) =>
          val errorJson = response.json \ "error"
          val errorCode = (errorJson \ "code").asOpt[Int]
          val errorSub = (errorJson \ "error_subcode").asOpt[Int]

          // see https://developers.facebook.com/docs/reference/api/errors/
          // TODO: deal with other errors as we find them and decide on a reasonable action
          import FacebookSocialGraph.ErrorSubcodes._
          (errorCode, errorSub) match {
            case (_, Some(AppNotInstalled)) =>
              log.warn(s"App not authorized for social user $socialUserInfo; not fetching connections.")
              db.readWrite { implicit s =>
                socialRepo.save(
                  socialUserInfo.withState(SocialUserInfoStates.APP_NOT_AUTHORIZED).withLastGraphRefresh())
              }
              Seq()
            case _ =>
              db.readWrite { implicit s =>
                socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.FETCH_FAIL).withLastGraphRefresh())
              }
              throw e
          }
        case ex: Exception =>
          db.readWrite { implicit c =>
            socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.FETCH_FAIL).withLastGraphRefresh())
          }
          throw new Exception(s"Problem Fetching User Info for $socialUserInfo", ex)
      }
  }
}

trait SocialGraphPlugin extends Plugin {
  def asyncFetch(socialUserInfo: SocialUserInfo): Future[Seq[SocialConnection]]
}

class SocialGraphPluginImpl @Inject() (
    actorFactory: ActorFactory[SocialGraphActor],
    val schedulingProperties: SchedulingProperties)
  extends SocialGraphPlugin with Logging with SchedulingPlugin {

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



