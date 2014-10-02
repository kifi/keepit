package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.commanders.SendFriendConnectionMadeNotificationHelper
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance.timing
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ ContextDoubleData, HeimdalServiceClient }
import com.keepit.model._
import com.keepit.social.{ SocialId, SocialNetworkType }
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import scala.concurrent.Future

class UserConnectionCreator @Inject() (
  db: Database,
  socialRepo: SocialUserInfoRepo,
  socialConnectionRepo: SocialConnectionRepo,
  userConnectionRepo: UserConnectionRepo,
  userValueRepo: UserValueRepo,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  basicUserRepo: BasicUserRepo,
  eliza: ElizaServiceClient,
  heimdal: HeimdalServiceClient,
  sendFriendConnectionMadeHelper: SendFriendConnectionMadeNotificationHelper)
    extends Logging {

  def createConnections(socialUserInfo: SocialUserInfo, socialIds: Seq[SocialId]): Seq[SocialConnection] = timing(s"createConnections($socialUserInfo) socialIds(${socialIds.length}):${socialIds.mkString(",")}") {
    if (socialIds.isEmpty) {
      Seq.empty
    } else {
      disableOldConnections(socialUserInfo, socialIds)
      val socialConnections = createNewConnections(socialUserInfo, socialIds)
      socialUserInfo.userId.map(userId => updateUserConnections(userId, Some(socialUserInfo.networkType)))
      socialConnections
    }
  }

  def getConnectionsLastUpdated(userId: Id[User]): Option[DateTime] = db.readOnlyMaster { implicit s =>
    userValueRepo.getValueStringOpt(userId, UserValueName.UPDATED_USER_CONNECTIONS) map parseStandardTime
  }

  def updateUserConnections(userId: Id[User], networkType: Option[SocialNetworkType] = None): Future[Seq[Unit]] = timing(s"updateUserConnections($userId)") {
    val (newConnections, user, newConnectionsObjects, connectionCount, socialConnectionCount) = db.readWrite { implicit s =>
      val existingConnections = userConnectionRepo.getConnectedUsers(userId)
      val socialConnections = socialConnectionRepo.getSociallyConnectedUsers(userId)
      val unfriendedConnections = userConnectionRepo.getUnfriendedUsers(userId)

      val newConnections = socialConnections -- existingConnections -- unfriendedConnections
      userConnectionRepo.addConnections(userId, newConnections)
      userValueRepo.setValue(userId, UserValueName.UPDATED_USER_CONNECTIONS, clock.now.toStandardTimeString)
      (newConnections, basicUserRepo.load(userId), newConnections.map(basicUserRepo.load),
        userConnectionRepo.getConnectionCount(userId), socialConnectionRepo.getUserConnectionCount(userId))
    }

    val emailsF = newConnections.map { connId =>
      log.info(s"Sending new connection to user $connId (to $userId)")
      eliza.sendToUser(connId, Json.arr("new_friends", Set(user)))
      sendFriendConnectionMadeHelper(userId, connId, networkType) map (_ => ())
    }.toSeq

    if (newConnections.nonEmpty) {
      eliza.sendToUser(userId, Json.arr("new_friends", newConnectionsObjects))
      heimdal.setUserProperties(userId,
        "kifiConnections" -> ContextDoubleData(connectionCount),
        "socialConnections" -> ContextDoubleData(socialConnectionCount)
      )
    }

    Future.sequence(emailsF)
  }

  private def extractFriendsWithConnections(socialUserInfo: SocialUserInfo, socialIds: Seq[SocialId])(implicit s: RSession): Seq[(SocialUserInfo, Option[SocialConnection])] = timing(s"extractFriendsWithConnections($socialUserInfo): socialIds(${socialIds.length}):${socialIds.mkString(",")}") {
    for {
      socialId <- socialIds
      sui <- socialRepo.getOpt(socialId, socialUserInfo.networkType)
    } yield {
      sui -> socialConnectionRepo.getConnectionOpt(socialUserInfo.id.get, sui.id.get)
    }
  }

  private def createNewConnections(socialUserInfo: SocialUserInfo, allSocialIds: Seq[SocialId]): Seq[SocialConnection] = timing(s"createNewConnections($socialUserInfo): allSocialIds(${allSocialIds.length}):${allSocialIds.mkString(",")}") {
    log.debug(s"looking for new (or reactive) connections for user ${socialUserInfo.fullName}")
    allSocialIds.grouped(100).flatMap { socialIds =>
      db.readWrite { implicit s =>
        log.info(s"[createNewConnections] Processing group of ${socialIds.length}")
        extractFriendsWithConnections(socialUserInfo, socialIds) map {
          case (_, Some(c)) if c.state == SocialConnectionStates.ACTIVE => Some(c)
          case (friend, Some(c)) =>
            log.info(s"activate connection between ${c.socialUser1} and ${c.socialUser2}")
            try {
              Some(socialConnectionRepo.save(c.withState(SocialConnectionStates.ACTIVE)))
            } catch {
              //probably race condition, report and move on
              case e: Throwable =>
                airbrake.notify(s"fail activate connection between ${c.socialUser1} and ${c.socialUser2}", e)
                None
            }
          case (friend, None) =>
            log.debug(s"a new connection was created between $socialUserInfo and ${friend.id.get}")
            try {
              Some(socialConnectionRepo.save(SocialConnection(socialUser1 = socialUserInfo.id.get, socialUser2 = friend.id.get)))
            } catch {
              //probably race condition, check what's already in the db, report and move on
              case e: Throwable =>
                val existingConnection = socialConnectionRepo.getConnectionOpt(socialUserInfo.id.get, friend.id.get)
                airbrake.notify(s"fail creating new connection between $socialUserInfo and ${friend.id.get}. found existing connection: $existingConnection", e)
                None
            }
        }
      }
    }.flatten.toSeq
  }

  private def disableOldConnections(socialUserInfo: SocialUserInfo, socialIds: Seq[SocialId]): Seq[SocialConnection] = timing(s"disableOldConnections($socialUserInfo): socialIds(${socialIds.length}):${socialIds.mkString(",")}") {
    log.debug(s"looking for connections to disable for ${socialUserInfo.fullName}")
    db.readWrite { implicit s =>
      val existingSocialUserInfos = {
        val socialUserInfos = socialConnectionRepo.getSocialConnectionInfos(socialUserInfo.id.get)
        socialUserInfos.map { socialUserInfo => socialUserInfo.socialId -> socialUserInfo }.toMap
      }

      val diff = existingSocialUserInfos.keys.toSeq diff socialIds
      log.debug(s"socialUserInfoForAllFriendsIds = $socialIds")
      log.debug(s"existingSocialUserInfoIds = ${existingSocialUserInfos.keys}")
      log.debug(s"size of diff = ${diff.length}")
      diff.map { socialId =>
        try {
          val friendSocialUserInfoId = existingSocialUserInfos(socialId).id
          log.debug(s"about to disable connection to ${socialUserInfo.id.get} for $friendSocialUserInfoId")
          socialConnectionRepo.getConnectionOpt(socialUserInfo.id.get, friendSocialUserInfoId) match {
            case Some(c) if c.state != SocialConnectionStates.INACTIVE =>
              log.info("connection is disabled")
              Some(socialConnectionRepo.save(c.withState(SocialConnectionStates.INACTIVE)))
            case Some(c) =>
              log.info("connection is already disabled")
              Some(c)
            case None =>
              airbrake.notify(s"trying to disable old connection: not find connection to ${socialUserInfo.id.get} for $friendSocialUserInfoId")
              None
          }
        } catch {
          case e: Throwable =>
            airbrake.notify(s"fail to disable old connection for user $socialUserInfo to his friend $socialId")
            None
        }
      }.flatten
    }
  }

}
