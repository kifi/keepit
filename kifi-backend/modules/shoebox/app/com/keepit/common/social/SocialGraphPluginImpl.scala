package com.keepit.common.social

import com.keepit.model.{SocialUserInfoStates, SocialConnection, SocialUserInfoRepo, SocialUserInfo}
import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.slick.Database
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.logging.Logging
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import akka.util.Timeout
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.pattern.ask
import com.keepit.social.{SocialNetworkType, SocialGraphPlugin, SocialGraph, SocialUserRawInfoStore}

private case class FetchUserInfo(socialUserInfo: SocialUserInfo)
private case class FetchUserInfoQuietly(socialUserInfo: SocialUserInfo)
private case object FetchAll

private[social] class SocialGraphActor @Inject() (
  airbrake: AirbrakeNotifier,
  graphs: Set[SocialGraph],
  db: Database,
  socialRepo: SocialUserInfoRepo,
  socialUserRawInfoStore: SocialUserRawInfoStore,
  socialUserImportFriends: SocialUserImportFriends,
  socialUserImportEmail: SocialUserImportEmail,
  socialUserCreateConnections: UserConnectionCreator)
  extends FortyTwoActor(airbrake) with Logging {

  private val networkTypeToGraph: Map[SocialNetworkType, SocialGraph] =
    graphs.map(graph => graph.networkType -> graph).toMap

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

    case m => throw new UnsupportedActorMessage(m)
  }

  def fetchUserInfo(socialUserInfo: SocialUserInfo): Seq[SocialConnection] = {
    try {
      require(socialUserInfo.credentials.isDefined,
        s"SocialUserInfo's credentials are not defined: $socialUserInfo")
      networkTypeToGraph.get(socialUserInfo.networkType).toSeq flatMap { graph =>
        graph.fetchSocialUserRawInfo(socialUserInfo).toSeq flatMap { rawInfo =>
          rawInfo.jsons flatMap graph.extractEmails map (socialUserImportEmail.importEmail(socialUserInfo.userId.get, _))

          socialUserRawInfoStore += (socialUserInfo.id.get -> rawInfo)

          val friends = rawInfo.jsons flatMap graph.extractFriends
          socialUserImportFriends.importFriends(friends)
          val connections = socialUserCreateConnections.createConnections(
            socialUserInfo, friends.map(_._1.socialId), graph.networkType)

          val updatedSui = rawInfo.jsons.foldLeft(socialUserInfo)(graph.updateSocialUserInfo)
          db.readWrite { implicit c =>
            socialRepo.save(updatedSui.withState(SocialUserInfoStates.FETCHED_USING_SELF).withLastGraphRefresh())
          }
          connections
        }
      }
    } catch {
      case ex: Exception =>
        db.readWrite { implicit c =>
          socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.FETCH_FAIL).withLastGraphRefresh())
        }
        throw new Exception(s"Problem updating SocialUserInfo: $socialUserInfo", ex)
    }
  }
}

class SocialGraphPluginImpl @Inject() (
  graphs: Set[SocialGraph],
  actor: ActorInstance[SocialGraphActor],
  val schedulingProperties: SchedulingProperties) //only on leader
  extends SocialGraphPlugin with Logging with SchedulingPlugin {

  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting SocialGraphPluginImpl")
    scheduleTask(actor.system, 10 seconds, 1 minutes, actor.ref, FetchAll)
  }
  override def onStop() {
    log.info("stopping SocialGraphPluginImpl")
  }

  def asyncRevokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] = {
    graphs.find(_.networkType == socialUserInfo.networkType).get.revokePermissions(socialUserInfo)
  }

  override def asyncFetch(socialUserInfo: SocialUserInfo): Future[Seq[SocialConnection]] = {
    require(socialUserInfo.credentials.isDefined,
      "social user info's credentials are not defined: %s".format(socialUserInfo))
    actor.ref.ask(FetchUserInfo(socialUserInfo))(5 minutes).mapTo[Seq[SocialConnection]]
  }
}
