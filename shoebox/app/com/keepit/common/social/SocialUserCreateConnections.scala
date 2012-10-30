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
    parentJsons map {json => 
      createConnectionsFromJson(socialUserInfo, json)
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
  
  private def extractFriends(parentJson: JsValue): Seq[JsValue] = (parentJson \ "friends" \ "data").asInstanceOf[JsArray].value
    
}