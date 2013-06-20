package com.keepit.common.social

import com.keepit.model.{SocialUserInfoStates, SocialConnection, SocialUserInfoRepo, SocialUserInfo}
import com.google.inject.Inject
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.db.slick.Database
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.logging.Logging
import com.keepit.common.actor.ActorFactory
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import akka.util.Timeout
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.pattern.ask

private case class FetchUserInfo(socialUserInfo: SocialUserInfo)
private case class FetchUserInfoQuietly(socialUserInfo: SocialUserInfo)
private case object FetchAll

private[social] class SocialGraphActor @Inject() (
                                                   healthcheckPlugin: HealthcheckPlugin,
                                                   graphs: Set[SocialGraph],
                                                   db: Database,
                                                   socialRepo: SocialUserInfoRepo,
                                                   socialUserRawInfoStore: SocialUserRawInfoStore,
                                                   socialUserImportFriends: SocialUserImportFriends,
                                                   socialUserImportEmail: SocialUserImportEmail,
                                                   socialUserCreateConnections: UserConnectionCreator)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

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

    case m => throw new Exception("unknown message %s".format(m))
  }

  def fetchUserInfo(socialUserInfo: SocialUserInfo): Seq[SocialConnection] = {
    try {
      require(socialUserInfo.credentials.isDefined,
        s"SocialUserInfo's credentials are not defined: $socialUserInfo")
      val graph = networkTypeToGraph(socialUserInfo.networkType)
      graph.fetchSocialUserRawInfo(socialUserInfo).toSeq flatMap { rawInfo =>
        rawInfo.jsons flatMap graph.extractEmails map (socialUserImportEmail.importEmail(socialUserInfo.userId.get, _))

        socialUserRawInfoStore += (socialUserInfo.id.get -> rawInfo)

        val friends = rawInfo.jsons flatMap graph.extractFriends
        socialUserImportFriends.importFriends(friends)
        val connections = socialUserCreateConnections.createConnections(
          socialUserInfo, friends.map(_._1.socialId), graph.networkType)

        db.readWrite { implicit c =>
          socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.FETCHED_USING_SELF).withLastGraphRefresh())
        }
        connections
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



