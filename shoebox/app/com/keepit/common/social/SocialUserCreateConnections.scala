package com.keepit.common.social

import com.keepit.common.db.CX
import com.keepit.common.logging.Logging
import com.keepit.model.{SocialConnection, SocialUserInfo, User, UserCxRepo}
import com.keepit.common.healthcheck.BabysitterTimeout

import play.api.Play.current
import play.api.libs.json.{JsArray, JsValue}

import akka.util.duration._


class SocialUserCreateConnections() extends Logging {

  def createConnections(socialUserInfo: SocialUserInfo, parentJson: Seq[JsValue]): Seq[SocialConnection] = {
	  disableConnectionsNotInJson(socialUserInfo, parentJson)
	  createConnectionsFromJson(socialUserInfo, parentJson)
  }

  def createConnectionsFromJson(socialUserInfo: SocialUserInfo, parentJson: Seq[JsValue]): Seq[SocialConnection] =
  {
    log.info("looking for new (or reactive) connections for user %s".format(socialUserInfo.fullName))

    implicit val timeout = BabysitterTimeout(30 seconds, 2 minutes)

    CX.withConnection { implicit conn =>
      parentJson flatMap extractFriends map extractSocialId map { SocialUserInfo.get(_, SocialNetworks.FACEBOOK)
      } map { sui =>
        SocialConnection.getConnectionOpt(socialUserInfo.id.get, sui.id.get) match {
          case Some(c) => {
            if (c.state != SocialConnection.States.ACTIVE) {
              log.info("activate connection between %s and %s".format(c.socialUser1, c.socialUser2))
              c.withState(SocialConnection.States.ACTIVE).save
            }
            else
            {
              log.info("connection between %s and %s is already active".format(c.socialUser1, c.socialUser2))
              c
            }
          }
          case None => {
            log.info("a new connection was created  between %s and %s".format(socialUserInfo.id.get, sui.id.get))
            SocialConnection(socialUser1 = socialUserInfo.id.get, socialUser2 = sui.id.get).save
          }
        }
      }
    }
  }

  def disableConnectionsNotInJson(socialUserInfo: SocialUserInfo, parentJson: Seq[JsValue]): Seq[SocialConnection] = {
    log.info("looking for connections to disable for user %s".format(socialUserInfo.fullName))
    CX.withConnection { implicit conn =>
	    val socialUserInfoForAllFriendsIds = parentJson flatMap extractFriends map extractSocialId
	    val existingSocialUserInfoIds = SocialConnection.getUserConnections(socialUserInfo.userId.get).toSeq map {sui => sui.socialId}
	    log.info("socialUserInfoForAllFriendsIds = %s".format(socialUserInfoForAllFriendsIds))
	    log.info("existingSocialUserInfoIds = %s".format(existingSocialUserInfoIds))
	    log.info("size of diff =%s".format((existingSocialUserInfoIds diff socialUserInfoForAllFriendsIds).length))
	    existingSocialUserInfoIds diff socialUserInfoForAllFriendsIds  map {
	      socialId => {
	        val friendSocialUserInfoId = SocialUserInfo.get(socialId, SocialNetworks.FACEBOOK).id.get
		      log.info("about to disbale connection between %s and for socialId = %s".format(socialUserInfo.id.get,friendSocialUserInfoId ));
	        SocialConnection.getConnectionOpt(socialUserInfo.id.get, friendSocialUserInfoId) match {
            case Some(c) => {
              if (c.state != SocialConnection.States.INACTIVE){
                log.info("connection is disabled")
            	  c.withState(SocialConnection.States.INACTIVE).save
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
