package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.model._
import play.api.libs.json._
import play.api.Mode._
import com.keepit.social.{ SocialUserRawInfo, SocialUserRawInfoStore }
import scala._
import scala.Some
import com.keepit.common.healthcheck.AirbrakeNotifier

case class SocialUserInfoWithJsValue(userInfo: SocialUserInfo, json: JsValue) {
  lazy val key = (userInfo.socialId.id + userInfo.networkType.name)
}

class SocialUserImportFriends @Inject() (
    db: Database,
    repo: SocialUserInfoRepo,
    airbrake: AirbrakeNotifier) extends Logging {

  /**
   * it seems to be common with linkedin api that they send duplication of users in their json
   */
  private def deDupe(users: Seq[SocialUserInfo]): Seq[SocialUserInfo] = {
    (users map { u => (u.socialId.id + u.networkType.name, u) } toMap).values.toList
  }

  def importFriends(socialUserInfo: SocialUserInfo, friends: Seq[SocialUserInfo]): Seq[SocialUserInfo] = {
    val deDuped = deDupe(friends)
    val socialUserInfosNeedToUpdate = db.readOnlyMaster { implicit s =>
      deDuped.flatMap {
        case friend =>
          getIfUpdateNeeded(friend)
      }
    }
    log.info(s"Importing ${socialUserInfo.userId} (${socialUserInfo.fullName})'s friends.")

    val socialUserInfos = db.readWriteBatch(socialUserInfosNeedToUpdate, attempts = 3) {
      case (session, friend) =>
        repo.getOpt(friend.socialId, friend.networkType)(session).orElse {
          try {
            Some(repo.save(friend)(session))
          } catch {
            case e: Exception =>
              repo.deleteCache(friend)(session)
              airbrake.notify(s"Error persisting single social user info for userId ${socialUserInfo.userId} (${socialUserInfo.fullName})", e)
              None
          }
        }
    }.values.toList.map { v =>
      v.toOption.flatten
    }.flatten

    log.info(s"Imported ${socialUserInfos.size} friends")
    socialUserInfos
  }

  private def getIfUpdateNeeded(friend: SocialUserInfo)(implicit s: RSession): Option[SocialUserInfo] = {
    repo.getOpt(friend.socialId, friend.networkType) match {
      case Some(existing) if existing.copy( //if name, picture or url are new -> update
        fullName = friend.fullName,
        pictureUrl = friend.pictureUrl,
        profileUrl = friend.profileUrl) != existing &&
        friend.fullName.nonEmpty /* LinkedIn API sometimes sends us bad data... */ =>
        Some(existing.copy(
          fullName = friend.fullName,
          pictureUrl = friend.pictureUrl,
          profileUrl = friend.profileUrl
        ))
      case None => Some(friend)
      case _ => None
    }
  }

}
