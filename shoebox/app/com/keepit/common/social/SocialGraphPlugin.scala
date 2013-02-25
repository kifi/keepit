package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.db.slick.DBConnection
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
import play.api.Plugin
import scala.concurrent.duration._
import com.keepit.common.akka.FortyTwoActor

//case object FetchAll
private case class FetchUserInfo(socialUserInfo: SocialUserInfo)
private case object FetchAll

private[social] class SocialGraphActor(graph: FacebookSocialGraph, db: DBConnection, socialRepo: SocialUserInfoRepo) extends FortyTwoActor with Logging {
  def receive() = {
    case FetchAll =>
      val unprocessedUsers = db.readOnly {implicit s =>
        socialRepo.getUnprocessed()
      }
      unprocessedUsers foreach { user =>
        self ! FetchUserInfo(user)
      }
      sender ! unprocessedUsers.size

    case FetchUserInfo(socialUserInfo) =>
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
        sender ! Right(connections)
      } catch {
        //todo(yonatan): healthcheck event, granular exception catching, frontend should be notified.
        case ex: Exception =>
          db.readWrite { implicit c =>
            socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.FETCH_FAIL).withLastGraphRefresh())
          }
          log.error("Problem Fetching User Info for %s".format(socialUserInfo), ex)
          sender ! Left(ex)
      }


    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait SocialGraphPlugin extends Plugin {
  def asyncFetch(socialUserInfo: SocialUserInfo): Future[Either[Seq[SocialConnection], Exception]]
  def fetchAll(): Unit
}

class SocialGraphPluginImpl @Inject() (system: ActorSystem, socialGraph: FacebookSocialGraph, db: DBConnection, socialRepo: SocialUserInfoRepo) 
    extends SocialGraphPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  private val actor = system.actorOf(Props { new SocialGraphActor(socialGraph, db, socialRepo) })

  // plugin lifecycle methods
  private var _cancellables: Seq[Cancellable] = Nil
  override def enabled: Boolean = true
  override def onStart(): Unit = {
    log.info("starting SocialGraphPluginImpl")
    _cancellables = Seq(
      system.scheduler.schedule(0 seconds, 1 minutes, actor, FetchAll)
    )
  }
  override def onStop(): Unit = {
    log.info("stopping SocialGraphPluginImpl")
    _cancellables.map(_.cancel)
  }

  def fetchAll(): Unit = {
    val future = actor.ask(FetchAll)(1 minutes).mapTo[Int]
    Await.result(future, 1 minutes)
  }

  override def asyncFetch(socialUserInfo: SocialUserInfo): Future[Either[Seq[SocialConnection], Exception]] = {
    require(socialUserInfo.credentials.isDefined,
      "social user info's credentials are not defined: %s".format(socialUserInfo))
    actor.ask(FetchUserInfo(socialUserInfo))(5 minutes).mapTo[Either[Seq[SocialConnection], Exception]]
  }
}



