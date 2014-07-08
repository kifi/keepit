package com.keepit.common.social

import org.joda.time.DateTime

import com.google.inject.Inject

import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.social.{SocialNetworkType, SocialId}

import play.api.libs.json.Json
import com.keepit.heimdal.{ContextDoubleData, HeimdalServiceClient}
import com.keepit.common.performance.timing

object UserConnectionCreator {
  private val UpdatedUserConnectionsKey = "updated_user_connections"
}

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
    heimdal: HeimdalServiceClient)
  extends Logging {

  def createConnections(socialUserInfo: SocialUserInfo, socialIds: Seq[SocialId],
      network: SocialNetworkType): Seq[SocialConnection] = timing(s"createConnections($socialUserInfo, $network) socialIds(${socialIds.length}):${socialIds.mkString(",")}") {
    if (socialIds.isEmpty) {
      Seq.empty
    } else {
      disableOldConnections(socialUserInfo, socialIds, network)
      val socialConnections = createNewConnections(socialUserInfo, socialIds, network)
      socialUserInfo.userId.map(updateUserConnections)
      socialConnections
    }
  }

  def getConnectionsLastUpdated(userId: Id[User]): Option[DateTime] = db.readOnly { implicit s =>
    userValueRepo.getValueStringOpt(userId, UserConnectionCreator.UpdatedUserConnectionsKey) map parseStandardTime
  }

  def updateUserConnections(userId: Id[User]) = timing(s"updateUserConnections($userId)") {
    db.readWrite { implicit s =>
      val existingConnections = userConnectionRepo.getConnectedUsers(userId)
      val socialConnections = socialConnectionRepo.getFortyTwoUserConnections(userId)

      val newConnections = socialConnections -- existingConnections
      userConnectionRepo.addConnections(userId, newConnections)
      userValueRepo.setValue(userId, UserConnectionCreator.UpdatedUserConnectionsKey, clock.now.toStandardTimeString)

      newConnections.foreach { connId =>
        log.info(s"Sending new connection to user $connId (to $userId)")
        eliza.sendToUser(connId, Json.arr("new_friends", Set(basicUserRepo.load(userId))))
      }

      if (newConnections.nonEmpty) {
        eliza.sendToUser(userId, Json.arr("new_friends", newConnections.map(basicUserRepo.load)))
        heimdal.setUserProperties(userId,
          "kifiConnections" -> ContextDoubleData(userConnectionRepo.getConnectionCount(userId)),
          "socialConnections" -> ContextDoubleData(socialConnectionRepo.getUserConnectionCount(userId))
        )
      }
    }
  }

  private def extractFriendsWithConnections(socialUserInfo: SocialUserInfo, socialIds: Seq[SocialId],
      network: SocialNetworkType)(implicit s: RSession): Seq[(SocialUserInfo, Option[SocialConnection])] = timing(s"extractFriendsWithConnections($socialUserInfo, $network): socialIds(${socialIds.length}):${socialIds.mkString(",")}") {
    for {
      socialId <- socialIds
      sui <- socialRepo.getOpt(socialId, network)
    } yield {
      sui -> socialConnectionRepo.getConnectionOpt(socialUserInfo.id.get, sui.id.get)
    }
  }

  private def createNewConnections(socialUserInfo: SocialUserInfo, allSocialIds: Seq[SocialId],
      network: SocialNetworkType): Seq[SocialConnection] = timing(s"createNewConnections($socialUserInfo, $network): allSocialIds(${allSocialIds.length}):${allSocialIds.mkString(",")}") {
    log.debug(s"looking for new (or reactive) connections for user ${socialUserInfo.fullName}")
    allSocialIds.grouped(100).flatMap { socialIds =>
      db.readWrite { implicit s =>
        log.info(s"[createNewConnections] Processing group of ${socialIds.length}")
        extractFriendsWithConnections(socialUserInfo, socialIds, network) map {
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
            log.info(s"a new connection was created between $socialUserInfo and ${friend.id.get}")
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
    }.toList.flatten
  }

  private def disableOldConnections(socialUserInfo: SocialUserInfo, socialIds: Seq[SocialId],
      network: SocialNetworkType): Seq[SocialConnection] = timing(s"disableOldConnections($socialUserInfo, $network): socialIds(${socialIds.length}):${socialIds.mkString(",")}") {
    log.debug(s"looking for connections to disable for ${socialUserInfo.fullName}")
    db.readWrite { implicit s =>
      val existingSocialUserInfoIds = socialConnectionRepo.getUserConnections(socialUserInfo.userId.get) collect {
        case sui if sui.networkType == network => sui.socialId
      }
      val diff = existingSocialUserInfoIds diff socialIds
      log.debug(s"socialUserInfoForAllFriendsIds = $socialIds")
      log.debug(s"existingSocialUserInfoIds = $existingSocialUserInfoIds")
      log.debug(s"size of diff = ${diff.length}")
      diff map { socialId =>
        try {
          val friendSocialUserInfoId = socialRepo.get(socialId, network).id.get
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
      } flatten
    }
  }

}
