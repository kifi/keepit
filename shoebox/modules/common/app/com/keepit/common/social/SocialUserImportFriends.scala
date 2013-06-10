package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.model._

import play.api.Play
import play.api.Play.current
import play.api.libs.json._

class SocialUserImportFriends @Inject() (
    db: Database,
    repo: SocialUserInfoRepo,
    store: SocialUserRawInfoStore,
    healthcheckPlugin: HealthcheckPlugin) extends Logging {

  def importFriends(friendsWithRawJson: Seq[(SocialUserInfo, JsValue)],
      network: SocialNetworkType): Seq[SocialUserRawInfo] = {
    val socialUserInfos = friendsWithRawJson filter {
      case (f, json) => infoNotInDb(f, network)
    } map { t =>
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

  private[social] def infoNotInDb(friend: SocialUserInfo, network: SocialNetworkType): Boolean = {
    db.readOnly { implicit s =>
      repo.getOpt(friend.socialId, network).isEmpty //todo: check if we want to merge jsons here
    }
  }

  private def createSocialUserRawInfo(socialUserInfo: SocialUserInfo, friend: JsValue) =
    SocialUserRawInfo(socialUserInfo = socialUserInfo, json = friend)

}
