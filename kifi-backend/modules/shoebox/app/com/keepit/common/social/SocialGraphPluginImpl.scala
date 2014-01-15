package com.keepit.common.social

import com.keepit.model._
import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.slick.Database
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.logging.Logging
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{SchedulerPlugin, SchedulingProperties}
import akka.util.Timeout
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.pattern.ask
import com.keepit.social.{SocialNetworkType, SocialGraphPlugin, SocialGraph, SocialUserRawInfoStore}
import scala.util.Try
import com.keepit.model.SocialConnection
import com.keepit.common.db.Id
import com.keepit.heimdal.{ContextStringData, HeimdalServiceClient}
import com.google.inject.Singleton
import com.keepit.common.performance.timing

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
  socialUserCreateConnections: UserConnectionCreator,
  userValueRepo: UserValueRepo,
  heimdal: HeimdalServiceClient)
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

  // hotspot: need optimization; gathering timing info for analysis
  def fetchUserInfo(socialUserInfo: SocialUserInfo): Seq[SocialConnection] = timing(s"fetchUserInfo($socialUserInfo)") {
    try {
      require(socialUserInfo.credentials.isDefined,
        s"SocialUserInfo's credentials are not defined: $socialUserInfo")
      val connectionsOpt = for {
        userId <- socialUserInfo.userId if !isImportAlreadyInProcess(userId, socialUserInfo.networkType)
        graph <- networkTypeToGraph.get(socialUserInfo.networkType)
        rawInfo <- {
          markGraphImportUserValue(userId, socialUserInfo.networkType, "fetching")
          graph.fetchSocialUserRawInfo(socialUserInfo)
        }
      } yield {
          markGraphImportUserValue(userId, socialUserInfo.networkType, "import_connections")
          rawInfo.jsons flatMap graph.extractEmails foreach (socialUserImportEmail.importEmail(userId, _))

          socialUserRawInfoStore += (socialUserInfo.id.get -> rawInfo)

          val friends = rawInfo.jsons flatMap graph.extractFriends
          socialUserImportFriends.importFriends(socialUserInfo, friends)
          val connections = socialUserCreateConnections.createConnections(socialUserInfo, friends.map(_._1.socialId), graph.networkType)

          val updatedSui = rawInfo.jsons.foldLeft(socialUserInfo)(graph.updateSocialUserInfo)
          val latestUserValues = rawInfo.jsons.map(graph.extractUserValues).reduce(_ ++ _)
          db.readWrite { implicit c =>
            latestUserValues.collect { case (key, value) if userValueRepo.getValue(userId, key) != Some(value) =>
              userValueRepo.setValue(userId, key, value)
              heimdal.setUserProperties(userId, key -> ContextStringData(value))
            }
            socialRepo.save(updatedSui.withState(SocialUserInfoStates.FETCHED_USING_SELF).withLastGraphRefresh())
          }
          connections
        }
      connectionsOpt getOrElse Seq.empty
    } catch {
      case ex: Exception =>
        db.readWrite { implicit s => socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.FETCH_FAIL).withLastGraphRefresh()) }
        throw new Exception(s"Error updating SocialUserInfo: ${socialUserInfo.id}, ${socialUserInfo.fullName}", ex)
    } finally {
      markGraphImportUserValue(socialUserInfo.userId.get, socialUserInfo.networkType, "false")
    }
  }

  private def markGraphImportUserValue(userId: Id[User], networkType: SocialNetworkType, state: String) = {
    db.readWrite(attempts = 3) { implicit session =>
      userValueRepo.setValue(userId, s"import_in_progress_${networkType.name}", state)
    }
  }

  private def isImportAlreadyInProcess(userId: Id[User], networkType: SocialNetworkType): Boolean = {
    val stateOpt = db.readOnly { implicit session =>
      userValueRepo.getValue(userId, s"import_in_progress_${networkType.name}")
    }
    stateOpt.map(_ != "false") getOrElse false
  }
}

@Singleton
class SocialGraphPluginImpl @Inject() (
  graphs: Set[SocialGraph],
  actor: ActorInstance[SocialGraphActor],
  val scheduling: SchedulingProperties) //only on leader
  extends SocialGraphPlugin with Logging with SchedulerPlugin {

  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting SocialGraphPluginImpl")
    scheduleTaskOnLeader(actor.system, 10 seconds, 1 minutes, actor.ref, FetchAll)
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
