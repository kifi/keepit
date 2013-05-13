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

import play.api.libs.json.{JsArray, JsValue}

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

  def createConnections(socialUserInfo: SocialUserInfo, parentJson: Seq[JsValue]): Seq[SocialConnection] = {
    disableConnectionsNotInJson(socialUserInfo, parentJson)
    val socialConnections = createConnectionsFromJson(socialUserInfo, parentJson)
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
      if (newConnections.nonEmpty) userChannel.push(userId, Json.arr("new_friends", newConnections.map(basicUserRepo.load)))
      newConnections.foreach { connId =>
        log.info("Sending new connection to user $connId (to $userId)")
        userChannel.push(connId, Json.arr("new_friends", Set(basicUserRepo.load(userId))))
      }
      userConnectionRepo.addConnections(userId, newConnections)
      userValueRepo.setValue(userId, UserConnectionCreator.UpdatedUserConnectionsKey, clock.now.toStandardTimeString)
    }
  }

  private def extractFriendsWithConnections(socialUserInfo: SocialUserInfo, parentJson: Seq[JsValue]): Seq[(SocialUserInfo, Option[SocialConnection])] = {
    implicit val timeout = BabysitterTimeout(30 seconds, 2 minutes)
    db.readOnly { implicit s =>
      parentJson flatMap extractFriends map extractSocialId map {
        socialRepo.get(_, SocialNetworks.FACEBOOK)
      } map { sui =>
        (sui, socialConnectionRepo.getConnectionOpt(socialUserInfo.id.get, sui.id.get))
      }
    }
  }

  private def createConnectionsFromJson(socialUserInfo: SocialUserInfo, parentJson: Seq[JsValue]): Seq[SocialConnection] = {
    log.info(s"looking for new (or reactive) connections for user ${socialUserInfo.fullName}")
    extractFriendsWithConnections(socialUserInfo, parentJson) map {
      case (friend, Some(c)) =>
        c.state match {
          case SocialConnectionStates.ACTIVE => c
          case _ =>
            log.debug(s"activate connection between ${c.socialUser1} and ${c.socialUser2}")
            db.readWrite { implicit s =>
              socialConnectionRepo.save(c.withState(SocialConnectionStates.ACTIVE))
            }
        }
      case (friend, None) =>
        log.debug(s"a new connection was created between ${socialUserInfo} and friend.id.get")
        db.readWrite { implicit s =>
          socialConnectionRepo.save(SocialConnection(socialUser1 = socialUserInfo.id.get, socialUser2 = friend.id.get))
        }
    }
  }

  def disableConnectionsNotInJson(socialUserInfo: SocialUserInfo, parentJson: Seq[JsValue]): Seq[SocialConnection] = {
    log.info("looking for connections to disable for user %s".format(socialUserInfo.fullName))
    db.readWrite { implicit s =>
      val socialUserInfoForAllFriendsIds = parentJson flatMap extractFriends map extractSocialId
	    val existingSocialUserInfoIds = socialConnectionRepo.getUserConnections(socialUserInfo.userId.get).toSeq map {sui => sui.socialId}
	    log.debug("socialUserInfoForAllFriendsIds = %s".format(socialUserInfoForAllFriendsIds))
	    log.debug("existingSocialUserInfoIds = %s".format(existingSocialUserInfoIds))
	    log.info("size of diff =%s".format((existingSocialUserInfoIds diff socialUserInfoForAllFriendsIds).length))
	    existingSocialUserInfoIds diff socialUserInfoForAllFriendsIds  map {
	      socialId => {
	        val friendSocialUserInfoId = socialRepo.get(socialId, SocialNetworks.FACEBOOK).id.get
		      log.info("about to disbale connection between %s and for socialId = %s".format(socialUserInfo.id.get,friendSocialUserInfoId ));
	        socialConnectionRepo.getConnectionOpt(socialUserInfo.id.get, friendSocialUserInfoId) match {
            case Some(c) => {
              if (c.state != SocialConnectionStates.INACTIVE){
                log.info("connection is disabled")
            	  socialConnectionRepo.save(c.withState(SocialConnectionStates.INACTIVE))
              }
              else {
                log.info("connection is already disabled")
                c
              }
            }
            case _ => throw new Exception("could not find the SocialConnection between %s and for socialId = %s".format(socialUserInfo.id.get,friendSocialUserInfoId ));
          }
	      }
	    }
	  }
	}
  private def extractFriends(parentJson: JsValue): Seq[JsValue] = (parentJson \\ "data").head.asInstanceOf[JsArray].value
  private def extractSocialId(friend: JsValue): SocialId = SocialId((friend \ "id").as[String])

}
