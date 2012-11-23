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

  def createConnections(socialUserInfo: SocialUserInfo, parentJsons: Seq[JsValue]): Seq[SocialConnection] =
    parentJsons map { 
	  json => {
	    println ("XXX")
		disableConnectionsNotInJson(socialUserInfo, json)
		println ("YYY")
		createConnectionsFromJson(socialUserInfo, json)
      }
    } flatten

  def createConnectionsFromJson(socialUserInfo: SocialUserInfo, parentJson: JsValue): Seq[SocialConnection] =
    CX.withConnection { implicit conn =>
      extractFriends(parentJson) map { friend =>
        SocialUserInfo.get(SocialId((friend \ "id").as[String]), SocialNetworks.FACEBOOK)
      } map { friend =>
        SocialConnection.getConnectionOpt(socialUserInfo.id.get, friend.id.get) match {
          case Some(c) => c
          case None => SocialConnection(socialUser1 = socialUserInfo.id.get, socialUser2 = friend.id.get).save
        }
      }
    }

  def disableConnectionsNotInJson(socialUserInfo: SocialUserInfo, parentJson: JsValue): Seq[SocialUserInfo] =
    CX.withConnection { implicit conn =>
    {
      println ("*** in disableConnectionsNotInJson")
	  val xyz = extractFriends(parentJson) map { friend => SocialUserInfo.get(SocialId((friend \ "id").as[String]), SocialNetworks.FACEBOOK) }
      println ("socialUserInfo is %s".format(socialUserInfo))
	  SocialConnection.getFortyTwoUserConnections(socialUserInfo.userId.get).toSeq map {
	    userId => {println("userId  %s".format(userId)); SocialUserInfo.getByUser(userId).head} 
	  } filter { 
	    socialUserInfo => xyz.forall { x => x != socialUserInfo } 
	  } map { 
	    sui => {println("disableing connection for %s".format(sui)); sui.withState(SocialUserInfo.States.INACTIVE).save; }} 
	}
  }

  private def extractFriends(parentJson: JsValue): Seq[JsValue] = (parentJson \\ "data").head.asInstanceOf[JsArray].value

}