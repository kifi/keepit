package com.keepit.common.social

import scala.collection.mutable.MutableList
import com.keepit.search.ArticleStore
import com.keepit.common.logging.Logging
import com.keepit.search.Article
import com.keepit.model.SocialUserInfo
import com.keepit.model.NormalizedURI
import play.api.Plugin
import play.api.templates.Html
import akka.util.Timeout
import akka.actor._
import akka.actor.Actor._
import akka.actor.ActorRef
import akka.util.duration._
import akka.pattern.ask
import akka.dispatch.Await
import play.api.libs.concurrent._
import org.joda.time.DateTime
import akka.dispatch.Future
import com.google.inject.Inject
import com.google.inject.Provider
import scala.collection.mutable.{Map => MutableMap}
import com.keepit.model.User
import com.keepit.inject._
import com.keepit.common.db.CX
import com.keepit.common.db.CX._
import play.api.Play.current
import play.api.libs.json.JsArray
import securesocial.core.{SocialUser, UserId, AuthenticationMethod, OAuth2Info}
import play.api.libs.json.JsValue
import java.sql.Connection
import com.keepit.common.logging.Logging


class SocialUserImportFriends() extends Logging {
  
  def importFriends(parentJson: JsValue): Seq[SocialUserRawInfo] = {
    val socialUserInfos = CX.withConnection { implicit conn =>
      extractFriends(parentJson) filter infoNotInDb map createSocialUserInfo map {t => (t._1.save, t._2)}
    }
    
    val socialUserRawInfos = socialUserInfos map { case (info, friend) => createSocialUserRawInfo(info, friend) }
    
    val store = inject[SocialUserRawInfoStore]
    socialUserRawInfos map { info =>
      store += (info.socialUserInfoId.get -> info)
    }
    socialUserRawInfos
  }
  
  private[social] def infoNotInDb(friend: JsValue)(implicit conn: Connection): Boolean = {
    val socialId = try {
      SocialId((friend \ "id").as[String])
    } catch {
      case e =>
        log.error("Can't parse username from friend json %s".format(friend))
        throw e
    }
    SocialUserInfo.getOpt(socialId, SocialNetworks.FACEBOOK).isEmpty //todo: check if we want to merge jsons here    
  }
  
  private def extractFriends(parentJson: JsValue): Seq[JsValue] = (parentJson \ "friends" \ "data").asInstanceOf[JsArray].value
  
  private def createSocialUserInfo(friend: JsValue): (SocialUserInfo, JsValue) = (SocialUserInfo(fullName = (friend \ "name").as[String], socialId = SocialId((friend \ "id").as[String]), 
                                                networkType = SocialNetworks.FACEBOOK, state = SocialUserInfo.States.FETCHED_USING_FRIEND), friend)
                                                
  private def createSocialUserRawInfo(socialUserInfo: SocialUserInfo, friend: JsValue) = SocialUserRawInfo(socialUserInfo = socialUserInfo, json = friend)
  
}