package com.keepit.common.social

import scala.collection.mutable.MutableList
import com.keepit.search.ArticleStore
import com.keepit.search.Article
import com.keepit.model._
import play.api.Plugin
import play.api.templates.Html
import akka.util.Timeout
import akka.actor._
import akka.actor.Actor._
import akka.actor.ActorRef
import play.api.libs.concurrent.Execution.Implicits._
import akka.pattern.ask
import scala.concurrent.Await
import play.api.libs.concurrent._
import org.joda.time.DateTime
import akka.dispatch.Future
import com.google.inject.Inject
import com.google.inject.Provider
import scala.collection.mutable.{Map => MutableMap}
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import play.api.Play
import play.api.Play.current
import play.api.libs.json.JsArray
import securesocial.core.{SocialUser, UserId, AuthenticationMethod, OAuth2Info}
import play.api.libs.json.JsValue
import java.sql.Connection
import com.keepit.common.logging.Logging


class SocialUserImportFriends() extends Logging {

  def importFriends(parentJsons: Seq[JsValue]): Seq[SocialUserRawInfo] = parentJsons map importFriendsFromJson flatten

  private def importFriendsFromJson(parentJson: JsValue): Seq[SocialUserRawInfo] = {
    val repo = inject[SocialUserInfoRepo]
    val socialUserInfos = extractFriends(parentJson) filter infoNotInDb map createSocialUserInfo map { t =>
      (inject[Database].readWrite {implicit s => repo.save(t._1)}, t._2)
    }

    val socialUserRawInfos = socialUserInfos map { case (info, friend) => createSocialUserRawInfo(info, friend) }

    val store = inject[SocialUserRawInfoStore]
    socialUserRawInfos map { info =>
      log.info("Adding user %s (%s) to S3".format(info.fullName, info.socialUserInfoId.get))
      if (!Play.isDev) {
        store += (info.socialUserInfoId.get -> info)
      }
    }

    log.info("Imported %s friends".format(socialUserRawInfos.size))

    socialUserRawInfos
  }

  private[social] def infoNotInDb(friend: JsValue): Boolean = {
    val socialId = try {
      SocialId((friend \ "id").as[String])
    } catch {
      case e: Throwable =>
        log.error("Can't parse username from friend json %s".format(friend))
        throw e
    }
    inject[Database].readOnly {implicit s =>
      inject[SocialUserInfoRepo].getOpt(socialId, SocialNetworks.FACEBOOK).isEmpty //todo: check if we want to merge jsons here
    }
  }

  private def extractFriends(parentJson: JsValue): Seq[JsValue] = (parentJson \\ "data").head.asInstanceOf[JsArray].value

  private def createSocialUserInfo(friend: JsValue): (SocialUserInfo, JsValue) = (SocialUserInfo(fullName = (friend \ "name").as[String], socialId = SocialId((friend \ "id").as[String]),
                                                networkType = SocialNetworks.FACEBOOK, state = SocialUserInfoStates.FETCHED_USING_FRIEND), friend)

  private def createSocialUserRawInfo(socialUserInfo: SocialUserInfo, friend: JsValue) = SocialUserRawInfo(socialUserInfo = socialUserInfo, json = friend)

}
