package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.inject._
import com.keepit.model._

import akka.actor._
import scala.concurrent.Await
import scala.concurrent.Future
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import scala.concurrent.duration._
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.plugin.SchedulingPlugin

private case class FetchUserInfo(socialUserInfo: SocialUserInfo)
private case class FetchUserInfoQuietly(socialUserInfo: SocialUserInfo)
private case object FetchAll

private[social] class SocialGraphActor(graph: FacebookSocialGraph, db: Database, socialRepo: SocialUserInfoRepo) extends FortyTwoActor with Logging {
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

  def fetchUserInfo(socialUserInfo: SocialUserInfo): Either[Exception, Seq[SocialConnection]] = {
      try {
        require(socialUserInfo.credentials.isDefined,
          "social user info's credentials are not defined: %s".format(socialUserInfo))
        log.info("fetching raw info for %s".format(socialUserInfo))
        val rawInfo = graph.fetchSocialUserRawInfo(socialUserInfo)
        log.info("fetched raw info %s for %s".format(rawInfo, socialUserInfo))
        val store = inject[SocialUserRawInfoStore]
        store += (socialUserInfo.id.get -> rawInfo)

        inject[SocialUserImportFriends].importFriends(rawInfo.jsons)
        val connections = inject[SocialUserCreateConnections].createConnections(socialUserInfo, rawInfo.jsons)
        inject[SocialUserImportEmail].importEmail(socialUserInfo.userId.get, rawInfo.jsons)
        db.readWrite { implicit c =>
          socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.FETCHED_USING_SELF).withLastGraphRefresh())
        }
        Right(connections)
      } catch {
        //todo(yonatan): healthcheck event, granular exception catching, frontend should be notified.
        case ex: Exception =>
          db.readWrite { implicit c =>
            socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.FETCH_FAIL).withLastGraphRefresh())
          }
          log.error("Problem Fetching User Info for %s".format(socialUserInfo), ex)
          Left(ex)
      }
  }
}

trait SocialGraphPlugin extends SchedulingPlugin {
  def asyncFetch(socialUserInfo: SocialUserInfo): Future[Either[Exception, Seq[SocialConnection]]]
}

class SocialGraphPluginImpl @Inject() (system: ActorSystem, socialGraph: FacebookSocialGraph, db: Database, socialRepo: SocialUserInfoRepo)
    extends SocialGraphPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  private val actor = system.actorOf(Props { new SocialGraphActor(socialGraph, db, socialRepo) })

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting SocialGraphPluginImpl")
    scheduleTask(system, 10 seconds, 1 minutes, actor, FetchAll)
  }
  override def onStop() {
    log.info("stopping SocialGraphPluginImpl")
  }

  override def asyncFetch(socialUserInfo: SocialUserInfo): Future[Either[Exception, Seq[SocialConnection]]] = {
    require(socialUserInfo.credentials.isDefined,
      "social user info's credentials are not defined: %s".format(socialUserInfo))
    actor.ask(FetchUserInfo(socialUserInfo))(5 minutes).mapTo[Either[Exception, Seq[SocialConnection]]]
  }
}



