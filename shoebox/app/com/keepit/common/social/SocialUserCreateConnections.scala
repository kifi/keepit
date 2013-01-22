package com.keepit.common.social

import play.api.Play.current
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.common.healthcheck.BabysitterTimeout
import play.api.Play.current
import play.api.libs.json.{JsArray, JsValue}
import akka.util.duration._
import com.keepit.common.db.slick.DBConnection


class SocialUserCreateConnections() extends Logging {

  def createConnections(socialUserInfo: SocialUserInfo, parentJson: Seq[JsValue]): Seq[SocialConnection] = {
	  disableConnectionsNotInJson(socialUserInfo, parentJson)
	  createConnectionsFromJson(socialUserInfo, parentJson)
  }

  def createConnectionsFromJson(socialUserInfo: SocialUserInfo, parentJson: Seq[JsValue]): Seq[SocialConnection] =
  {
    log.info("looking for new (or reactive) connections for user %s".format(socialUserInfo.fullName))

    implicit val timeout = BabysitterTimeout(30 seconds, 2 minutes)

    inject[DBConnection].readWrite { implicit s =>
      val socialRepo = inject[SocialUserInfoRepo]
      val connectionRepo = inject[SocialConnectionRepo]
      parentJson flatMap extractFriends map extractSocialId map { socialRepo.get(_, SocialNetworks.FACEBOOK)
      } map { sui =>
        connectionRepo.getConnectionOpt(socialUserInfo.id.get, sui.id.get) match {
          case Some(c) => {
            if (c.state != SocialConnectionStates.ACTIVE) {
              log.info("activate connection between %s and %s".format(c.socialUser1, c.socialUser2))
              connectionRepo.save(c.withState(SocialConnectionStates.ACTIVE))
            }
            else
            {
              log.info("connection between %s and %s is already active".format(c.socialUser1, c.socialUser2))
              c
            }
          }
          case None => {
            log.info("a new connection was created  between %s and %s".format(socialUserInfo.id.get, sui.id.get))
            connectionRepo.save(SocialConnection(socialUser1 = socialUserInfo.id.get, socialUser2 = sui.id.get))
          }
        }
      }
    }
  }

  def disableConnectionsNotInJson(socialUserInfo: SocialUserInfo, parentJson: Seq[JsValue]): Seq[SocialConnection] = {
    log.info("looking for connections to disable for user %s".format(socialUserInfo.fullName))
    inject[DBConnection].readWrite { implicit s =>
      val socialRepo = inject[SocialUserInfoRepo]
      val connectionRepo = inject[SocialConnectionRepo]
	    val socialUserInfoForAllFriendsIds = parentJson flatMap extractFriends map extractSocialId
	    val existingSocialUserInfoIds = connectionRepo.getUserConnections(socialUserInfo.userId.get).toSeq map {sui => sui.socialId}
	    log.info("socialUserInfoForAllFriendsIds = %s".format(socialUserInfoForAllFriendsIds))
	    log.info("existingSocialUserInfoIds = %s".format(existingSocialUserInfoIds))
	    log.info("size of diff =%s".format((existingSocialUserInfoIds diff socialUserInfoForAllFriendsIds).length))
	    existingSocialUserInfoIds diff socialUserInfoForAllFriendsIds  map {
	      socialId => {
	        val friendSocialUserInfoId = socialRepo.get(socialId, SocialNetworks.FACEBOOK).id.get
		      log.info("about to disbale connection between %s and for socialId = %s".format(socialUserInfo.id.get,friendSocialUserInfoId ));
	        connectionRepo.getConnectionOpt(socialUserInfo.id.get, friendSocialUserInfoId) match {
            case Some(c) => {
              if (c.state != SocialConnectionStates.INACTIVE){
                log.info("connection is disabled")
            	  connectionRepo.save(c.withState(SocialConnectionStates.INACTIVE))
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
