package com.keepit.common.social

import com.keepit.common.db.CX
import com.keepit.common.logging.Logging
import com.keepit.model.SocialConnection
import com.keepit.model.SocialUserInfo
import play.api.Play.current
import play.api.libs.json.JsArray
import play.api.libs.json.JsValue
import com.keepit.model.User

class SocialUserCreateConnections() extends Logging {

  def createConnections(socialUserInfo: SocialUserInfo, parentJson: Seq[JsValue]): Seq[SocialConnection] = {
    log.info("about to create  / disbale connections for socialUserInfo %s. handling ".format(socialUserInfo))
    log.info("now handling friends %s".format(parentJson))
	disableConnectionsNotInJson(socialUserInfo, parentJson)
	createConnectionsFromJson(socialUserInfo, parentJson) 
  }

  def createConnectionsFromJson(socialUserInfo: SocialUserInfo, parentJson: Seq[JsValue]): Seq[SocialConnection] =
  {
    log.info("create new connections for user %s".format(socialUserInfo))
    
    CX.withConnection { implicit conn =>
      parentJson flatMap extractFriends map extractSocialId map { SocialUserInfo.get(_, SocialNetworks.FACEBOOK) 
      } map { sui =>
        SocialConnection.getConnectionOpt(socialUserInfo.id.get, sui.id.get) match {
          case Some(c) => c
          case None => {log.info("a new connection was created %s".format(sui)) 
            SocialConnection(socialUser1 = socialUserInfo.id.get, socialUser2 = sui.id.get).save}
        }
      }
    }
  }
  
  def disableConnectionsNotInJson(socialUserInfo: SocialUserInfo, parentJson: Seq[JsValue]): Seq[SocialUserInfo] = {
    log.info("disable connections for user %s".format(socialUserInfo))
    log.info("user Id is %s".format(socialUserInfo.userId.get))
    CX.withConnection { implicit conn =>
    {
	  val socialUserInfoForAllFriendsIds = parentJson flatMap extractFriends map extractSocialId  
	  val existingSocialUserInfoIds = SocialConnection.getUserConnections(socialUserInfo.userId.get).toSeq map {sui => sui.socialId}
	  log.info("socialUserInfoForAllFriendsIds = %s".format(socialUserInfoForAllFriendsIds))
	  log.info("existingSocialUserInfoIds = %s".format(existingSocialUserInfoIds))
	  log.info("size of diff =%s".format((existingSocialUserInfoIds diff socialUserInfoForAllFriendsIds).length))
//	    userId => SocialUserInfo.getByUser(userId).head 
	  existingSocialUserInfoIds diff socialUserInfoForAllFriendsIds  map { 
		  socialId => {
			  log.info("disableing connection for socialId = %s".format(socialId)); 
			  SocialUserInfo.get(socialId, SocialNetworks.FACEBOOK).withState(SocialUserInfo.States.INACTIVE).save
		  } 
	    }
	  } 
	}
  }

  private def extractFriends(parentJson: JsValue): Seq[JsValue] = (parentJson \\ "data").head.asInstanceOf[JsArray].value
  private def extractSocialId(friend: JsValue): SocialId = SocialId((friend \ "id").as[String])

}
