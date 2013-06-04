package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.model._

import play.api.Play
import play.api.Play.current
import play.api.libs.json.{JsObject, JsArray, JsValue}

class SocialUserImportFriends @Inject() (
    db: Database,
    repo: SocialUserInfoRepo,
    store: SocialUserRawInfoStore) extends Logging {

  def importFriends(parentJsons: Seq[JsValue]): Seq[SocialUserRawInfo] = parentJsons map importFriendsFromJson flatten

  private def importFriendsFromJson(parentJson: JsValue): Seq[SocialUserRawInfo] = {
    val socialUserInfos = extractFriends(parentJson) filter infoNotInDb map createSocialUserInfo map { t =>
      (db.readWrite {implicit s => repo.save(t._1)}, t._2)
    }

    val socialUserRawInfos = socialUserInfos map { case (info, friend) => createSocialUserRawInfo(info, friend) }

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
    db.readOnly {implicit s =>
      repo.getOpt(socialId, SocialNetworks.FACEBOOK).isEmpty //todo: check if we want to merge jsons here
    }
  }

  private def extractFriends(parentJson: JsValue): Seq[JsValue] =
    (parentJson \\ "data").head match {
      case JsArray(values) => values
      case _ => Seq() // Workaround for bug in Facebook graph API when a user has no friends.
    }

  private def createSocialUserInfo(friend: JsValue): (SocialUserInfo, JsValue) =
    (SocialUserInfo(
      fullName = (friend \ "name").asOpt[String].getOrElse(""),
      socialId = SocialId((friend \ "id").as[String]),
      networkType = SocialNetworks.FACEBOOK,
      state = SocialUserInfoStates.FETCHED_USING_FRIEND
    ), friend)

  private def createSocialUserRawInfo(socialUserInfo: SocialUserInfo, friend: JsValue) =
    SocialUserRawInfo(socialUserInfo = socialUserInfo, json = friend)

}
