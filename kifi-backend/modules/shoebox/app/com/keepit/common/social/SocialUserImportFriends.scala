package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.model._
import play.api.libs.json._
import play.api.Mode._
import com.keepit.social.{SocialUserRawInfo, SocialUserRawInfoStore}

class SocialUserImportFriends @Inject() (
    db: Database,
    repo: SocialUserInfoRepo,
    store: SocialUserRawInfoStore,
    healthcheckPlugin: HealthcheckPlugin
) extends Logging {

  def importFriends(friendsWithRawJson: Seq[(SocialUserInfo, JsValue)]): Seq[SocialUserRawInfo] = {
    val socialUserInfos = db.readOnly { implicit s =>
      friendsWithRawJson flatMap { case (f, json) => getIfUpdateNeeded(f).map(_ -> json) }
    }.grouped(100).toIndexedSeq.flatMap { friendsWithJson =>
      db.readWrite { implicit s =>
        friendsWithJson.map { case (info, value) => (repo.save(info), value) }
      }
    }

    val socialUserRawInfos = socialUserInfos map { case (info, friend) => createSocialUserRawInfo(info, friend) }

    socialUserRawInfos map { info =>
      log.info(s"Adding user ${info.fullName} (${info.socialUserInfoId.get}) to S3")
      store += (info.socialUserInfoId.get -> info)
    }

    log.info(s"Imported ${socialUserRawInfos.size} friends")

    socialUserRawInfos
  }

  private def getIfUpdateNeeded(friend: SocialUserInfo)(implicit s: RSession): Option[SocialUserInfo] = {
    repo.getOpt(friend.socialId, friend.networkType) match {
      case Some(existing) if existing.copy(
          fullName = friend.fullName, pictureUrl = friend.pictureUrl, profileUrl = friend.profileUrl) != existing &&
          friend.fullName.nonEmpty /* LinkedIn API sometimes sends us bad data... */ =>
        Some(existing.copy(
          fullName = friend.fullName,
          pictureUrl = friend.pictureUrl,
          profileUrl = friend.profileUrl,
          state = SocialUserInfoStates.FETCHED_USING_FRIEND
        ))
      case None => Some(friend)
      case _ => None
    }
  }

  private def createSocialUserRawInfo(socialUserInfo: SocialUserInfo, friend: JsValue) =
    SocialUserRawInfo(socialUserInfo = socialUserInfo, json = friend)

}
