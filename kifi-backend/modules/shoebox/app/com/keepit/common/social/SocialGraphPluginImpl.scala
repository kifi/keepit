package com.keepit.common.social

import com.keepit.commanders.{ UserEmailAddressCommander, UserCommander }
import com.keepit.common.auth.AuthException
import com.keepit.common.net.NonOKResponseException
import com.keepit.model._
import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.slick.Database
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.logging.Logging
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import akka.util.Timeout
import scala.concurrent.{ Promise, Future }
import scala.concurrent.duration._
import akka.pattern.ask
import com.keepit.social.{ SocialNetworks, SocialNetworkType, SocialGraphPlugin, SocialGraph }
import com.keepit.model.SocialConnection
import com.keepit.common.db.Id
import com.keepit.heimdal.{ ContextStringData, HeimdalServiceClient }
import com.google.inject.Singleton
import com.keepit.common.performance.timing
import com.keepit.common.time._
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.typeahead.SocialUserTypeahead

private case class FetchUserInfo(socialUserInfoId: Id[SocialUserInfo])
private case class FetchUserInfoQuietly(socialUserInfo: SocialUserInfo)
private case object FetchAll
private case object RefreshAll
private case object FetchAndRefreshAll

private[social] class SocialGraphActor @Inject() (
  airbrake: AirbrakeNotifier,
  graphs: Set[SocialGraph],
  db: Database,
  socialRepo: SocialUserInfoRepo,
  socialUserImportFriends: SocialUserImportFriends,
  socialUserCreateConnections: UserConnectionCreator,
  socialUserTypeahead: SocialUserTypeahead,
  userValueRepo: UserValueRepo,
  emailAddressCommander: UserEmailAddressCommander,
  heimdal: HeimdalServiceClient,
  clock: Clock)
    extends FortyTwoActor(airbrake) with Logging {

  private val networkTypeToGraph: Map[SocialNetworkType, SocialGraph] =
    graphs.map(graph => graph.networkType -> graph).toMap

  def receive() = {
    case FetchAndRefreshAll =>
      self ! FetchAll
      self ! RefreshAll

    case RefreshAll =>
      log.info("going to check which SocialUserInfo was not fetched lately")
      val needToBeRefreshed = db.readOnlyReplica { implicit s => socialRepo.getNeedToBeRefreshed }
      log.info(s"find ${needToBeRefreshed.size} users that need to be refreshed")
      needToBeRefreshed.foreach(self ! FetchUserInfoQuietly(_))

    case FetchAll =>
      val unprocessedUsers = db.readOnlyReplica { implicit s =>
        socialRepo.getUnprocessed()
      }
      log.info(s"about to fetch ${unprocessedUsers.size} unprocessed users: ${unprocessedUsers.map(_.userId)}")
      unprocessedUsers foreach { user =>
        self ! FetchUserInfoQuietly(user)
      }

    case FetchUserInfo(socialUserInfoId) =>
      val socialUserInfo = db.readOnlyMaster { implicit session =>
        socialRepo.getNoCache(socialUserInfoId)
      }
      fetchUserInfo(socialUserInfo)
      sender ! (())

    case FetchUserInfoQuietly(socialUserInfo) =>
      fetchUserInfo(socialUserInfo)

    case m => throw new UnsupportedActorMessage(m)
  }

  private def fetchUserInfo(socialUserInfo: SocialUserInfo): Unit = timing(s"fetchUserInfo($socialUserInfo)") {
    try {
      require(socialUserInfo.credentials.isDefined, s"SocialUserInfo's credentials are not defined: $socialUserInfo")
      require(socialUserInfo.state != SocialUserInfoStates.APP_NOT_AUTHORIZED, s"SocialUserInfo's state is not authorized, need to wait until user re-auth: $socialUserInfo")
      require(socialUserInfo.state != SocialUserInfoStates.INACTIVE, s"SocialUserInfo's state is inactive: $socialUserInfo")
      val connectionsOpt = for {
        userId <- socialUserInfo.userId if !isImportAlreadyInProcess(userId, socialUserInfo.networkType)
        graph <- networkTypeToGraph.get(socialUserInfo.networkType)
        _ <- Some(markGraphImportUserValue(userId, socialUserInfo.networkType, "fetching"))
        rawInfo <- graph.fetchSocialUserRawInfo(socialUserInfo)
      } yield {
        markGraphImportUserValue(userId, socialUserInfo.networkType, "import_connections")

        val updatedSui = rawInfo.jsons.foldLeft(socialUserInfo)(graph.updateSocialUserInfo)
        val latestUserValues = rawInfo.jsons.map(graph.extractUserValues).reduce(_ ++ _)
        db.readWrite(attempts = 3) { implicit c =>
          latestUserValues.collect {
            case (key, value) if userValueRepo.getValueStringOpt(userId, key) != Some(value) =>
              userValueRepo.setValue(userId, key, value)
              heimdal.setUserProperties(userId, key.name -> ContextStringData(value))
          }
          socialRepo.save(updatedSui.withState(SocialUserInfoStates.FETCHED_USING_SELF).withLastGraphRefresh())
        }

        val friendsSocialId = rawInfo.jsons.map { json =>
          val emails = graph.extractEmails(json)
          db.readWrite { implicit session =>
            emails.foreach(emailAddressCommander.intern(userId, _, verified = true))
          }

          val friends = graph.extractFriends(json)
          socialUserImportFriends.importFriends(socialUserInfo, friends)
          friends.map(_.socialId)
        }.toList.flatten

        val connections = socialUserCreateConnections.createConnections(socialUserInfo, friendsSocialId)

        socialUserTypeahead.refresh(userId)
        connections
      }
      connectionsOpt getOrElse Seq.empty
    } catch {
      case ex: NonOKResponseException if ex.response.status / 100 == 5 =>
        log.warn(s"Remote server error fetching SocialUserInfo: $socialUserInfo", ex)
        Seq()
      case ex: NonOKResponseException if ex.response.status / 100 == 4 =>
        db.readWrite { implicit s => socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.TOKEN_EXPIRED).withLastGraphRefresh()) }
        log.warn(s"SocialUserInfo token expired: $socialUserInfo", ex)
        Seq()
      case ae: AuthException if Option(ae.response).exists(_.status == 429) =>
        log.warn(s"[fetchUserInfo] Got rate limited for: $socialUserInfo")
        db.readWrite { implicit s => socialRepo.save(socialUserInfo.withLastGraphRefresh()) }
        Seq()
      case ae: AuthException =>
        log.warn(s"[fetchUserInfo] AuthException: $socialUserInfo", ae)
        db.readWrite { implicit s => socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.USER_NOT_FOUND).withLastGraphRefresh()) }
        Seq()
      case ex: Exception =>
        db.readWrite { implicit s => socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.FETCH_FAIL).withLastGraphRefresh()) }
        throw new Exception(s"Error updating SocialUserInfo: $socialUserInfo", ex)
    } finally {
      socialUserInfo.userId.foreach { userId =>
        db.readWrite(attempts = 3) { implicit session =>
          userValueRepo.clearValue(userId, UserValueName.importInProgress(socialUserInfo.networkType.name))
        }
      }
    }
  }

  private def markGraphImportUserValue(userId: Id[User], networkType: SocialNetworkType, state: String) = {
    db.readWrite(attempts = 3) { implicit session =>
      userValueRepo.setValue(userId, UserValueName.importInProgress(networkType.name), state)
    }
  }

  private def isImportAlreadyInProcess(userId: Id[User], networkType: SocialNetworkType): Boolean = {
    val stateOpt = db.readOnlyMaster { implicit session =>
      userValueRepo.getUserValue(userId, UserValueName.importInProgress(networkType.name))
    }
    stateOpt match {
      case None => false
      case Some(stateValue) if stateValue.value == "false" => false
      case Some(stateValue) if stateValue.updatedAt.isBefore(clock.now.minusHours(1)) =>
        markGraphImportUserValue(userId, networkType, "false")
        false
      case _ => true
    }
  }
}

@Singleton
class SocialGraphPluginImpl @Inject() (
  graphs: Set[SocialGraph],
  actor: ActorInstance[SocialGraphActor],
  serviceDiscovery: ServiceDiscovery,
  shoeboxServiceClient: ShoeboxServiceClient,
  val scheduling: SchedulingProperties) //only on leader
    extends SocialGraphPlugin with Logging with SchedulerPlugin {

  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() { //kill
    //    scheduleTaskOnOneMachine(actor.system, 100 seconds, 71 seconds, actor.ref, FetchAndRefreshAll, FetchAndRefreshAll.getClass.getSimpleName)
  }

  def asyncRevokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] = {
    graphs.find(_.networkType == socialUserInfo.networkType)
      .map(_.revokePermissions(socialUserInfo)).getOrElse(Promise.successful(()).future)
  }

  def asyncFetch(socialUserInfo: SocialUserInfo, broadcastToOthers: Boolean = true): Future[Unit] = {
    require(socialUserInfo.credentials.isDefined, s"social user info's credentials are not defined: $socialUserInfo")
    require(socialUserInfo.id.isDefined, s"social user info's id is not defined: $socialUserInfo")
    require(socialUserInfo.state != SocialUserInfoStates.APP_NOT_AUTHORIZED, s"SocialUserInfo's state is not authorized, need to wait until user re-auth: $socialUserInfo")
    require(socialUserInfo.state != SocialUserInfoStates.INACTIVE, s"SocialUserInfo's state is inactive: $socialUserInfo")

    if (serviceDiscovery.isLeader()) {
      log.info(s"[SocialGraphPluginImpl] Need to refresh SocialUserInfoId(${socialUserInfo.id.get}). I'm leader.")
      actor.ref.ask(FetchUserInfo(socialUserInfo.id.get))(5 minutes).map(_ => ())(ExecutionContext.immediate)
    } else if (broadcastToOthers) {
      log.info(s"[SocialGraphPluginImpl] Need to refresh SocialUserInfoId(${socialUserInfo.id.get}). Sending to leader.")
      shoeboxServiceClient.triggerSocialGraphFetch(socialUserInfo.id.get)
    } else {
      Future.successful(())
    }
  }
}
