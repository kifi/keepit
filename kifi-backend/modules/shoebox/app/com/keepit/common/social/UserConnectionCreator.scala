package com.keepit.common.social

import scala.concurrent.duration._

import org.joda.time.DateTime

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.BabysitterTimeout
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.realtime.UserChannel

import play.api.libs.json.Json
import com.keepit.social.{SocialNetworkType, SocialId}

object UserConnectionCreator {
  private val UpdatedUserConnectionsKey = "updated_user_connections"
}

@ImplementedBy(classOf[NoOpConnectionUpdater])
trait ConnectionUpdater {
  def updateConnectionsIfNecessary(userId: Id[User])
}

class NoOpConnectionUpdater extends ConnectionUpdater {
  def updateConnectionsIfNecessary(userId: Id[User]) {}
}

class UserConnectionCreator @Inject() (
    db: Database,
    socialRepo: SocialUserInfoRepo,
    socialConnectionRepo: SocialConnectionRepo,
    userConnectionRepo: UserConnectionRepo,
    userValueRepo: UserValueRepo,
    clock: Clock,
    userChannel: UserChannel,
    basicUserRepo: BasicUserRepo)
  extends ConnectionUpdater with Logging {

  def createConnections(socialUserInfo: SocialUserInfo, socialIds: Seq[SocialId],
      network: SocialNetworkType): Seq[SocialConnection] = {
    disableOldConnections(socialUserInfo, socialIds, network)
    val socialConnections = createNewConnections(socialUserInfo, socialIds, network)
    socialUserInfo.userId.map(updateUserConnections)
    socialConnections
  }

  def updateConnectionsIfNecessary(userId: Id[User]) {
    if (getConnectionsLastUpdated(userId).isEmpty) {
      updateUserConnections(userId)
    }
  }

  def getConnectionsLastUpdated(userId: Id[User]): Option[DateTime] = db.readOnly { implicit s =>
    userValueRepo.getValue(userId, UserConnectionCreator.UpdatedUserConnectionsKey) map parseStandardTime
  }

  def updateUserConnections(userId: Id[User]) {
    db.readWrite { implicit s =>
      val existingConnections = userConnectionRepo.getConnectedUsers(userId)
      val updatedConnections = socialConnectionRepo.getFortyTwoUserConnections(userId)

      userConnectionRepo.removeConnections(userId, existingConnections diff updatedConnections)

      val newConnections = updatedConnections diff existingConnections
      if (newConnections.nonEmpty) {
        userChannel.pushAndFanout(userId, Json.arr("new_friends", newConnections.map(basicUserRepo.load)))
      }
      newConnections.foreach { connId =>
        log.info(s"Sending new connection to user $connId (to $userId)")
        userChannel.pushAndFanout(connId, Json.arr("new_friends", Set(basicUserRepo.load(userId))))
      }
      userConnectionRepo.addConnections(userId, newConnections)
      userValueRepo.setValue(userId, UserConnectionCreator.UpdatedUserConnectionsKey, clock.now.toStandardTimeString)
    }
  }

  private def extractFriendsWithConnections(socialUserInfo: SocialUserInfo, socialIds: Seq[SocialId],
      network: SocialNetworkType): Seq[(SocialUserInfo, Option[SocialConnection])] = {
    implicit val timeout = BabysitterTimeout(30 seconds, 2 minutes)
    db.readOnly { implicit s =>
      for {
        socialId <- socialIds
        sui <- socialRepo.getOpt(socialId, network)
      } yield {
        sui -> socialConnectionRepo.getConnectionOpt(socialUserInfo.id.get, sui.id.get)
      }
    }
  }

  private def createNewConnections(socialUserInfo: SocialUserInfo, socialIds: Seq[SocialId],
      network: SocialNetworkType): Seq[SocialConnection] = {
    log.info(s"looking for new (or reactive) connections for user ${socialUserInfo.fullName}")
    extractFriendsWithConnections(socialUserInfo, socialIds, network) map {
      case (_, Some(c)) if c.state == SocialConnectionStates.ACTIVE => c
      case (friend, Some(c)) =>
        log.info(s"activate connection between ${c.socialUser1} and ${c.socialUser2}")
        db.readWrite { implicit s =>
          socialConnectionRepo.save(c.withState(SocialConnectionStates.ACTIVE))
        }
      case (friend, None) =>
        log.info(s"a new connection was created between $socialUserInfo and ${friend.id.get}")
        db.readWrite { implicit s =>
          socialConnectionRepo.save(SocialConnection(socialUser1 = socialUserInfo.id.get, socialUser2 = friend.id.get))
        }
    }
  }

  def disableOldConnections(socialUserInfo: SocialUserInfo, socialIds: Seq[SocialId],
      network: SocialNetworkType): Seq[SocialConnection] = {
    log.info(s"looking for connections to disable for ${socialUserInfo.fullName}")
    db.readWrite { implicit s =>
      val existingSocialUserInfoIds = socialConnectionRepo.getUserConnections(socialUserInfo.userId.get) collect {
        case sui if sui.networkType == network => sui.socialId
      }
      val diff = existingSocialUserInfoIds diff socialIds
      log.debug(s"socialUserInfoForAllFriendsIds = $socialIds")
      log.debug(s"existingSocialUserInfoIds = $existingSocialUserInfoIds")
      log.info(s"size of diff = ${diff.length}")
      diff map { socialId =>
        val friendSocialUserInfoId = socialRepo.get(socialId, network).id.get
        log.info(s"about to disable connection to ${socialUserInfo.id.get} for $friendSocialUserInfoId")
        socialConnectionRepo.getConnectionOpt(socialUserInfo.id.get, friendSocialUserInfoId) match {
          case Some(c) if c.state != SocialConnectionStates.INACTIVE =>
            log.info("connection is disabled")
            socialConnectionRepo.save(c.withState(SocialConnectionStates.INACTIVE))
          case Some(c) =>
            log.info("connection is already disabled")
            c
          case None =>
            throw new Exception(s"could not find connection to ${socialUserInfo.id.get} for $friendSocialUserInfoId")
        }
      }
    }
  }

}
