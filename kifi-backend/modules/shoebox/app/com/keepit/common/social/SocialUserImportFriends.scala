package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.model._
import play.api.libs.json._
import play.api.Mode._
import com.keepit.social.{SocialUserRawInfo, SocialUserRawInfoStore}
import scala._
import scala.Some
import com.keepit.common.healthcheck.AirbrakeNotifier

case class SocialUserInfoWithJsValue(userInfo: SocialUserInfo, json: JsValue) {
  lazy val key = (userInfo.socialId.id + userInfo.networkType.name)
}

class SocialUserImportFriends @Inject() (
    db: Database,
    repo: SocialUserInfoRepo,
    store: SocialUserRawInfoStore,
    airbrake: AirbrakeNotifier
) extends Logging {

  /**
   * it seems to be common with linkedin api that they send duplication of users in their json
   */
  private def dedup(users: Seq[SocialUserInfoWithJsValue]): Seq[SocialUserInfoWithJsValue] = {
    val deduped = (users map { u => (u.key, u)} toMap).values.toList
    if (deduped.size != users.size) {
      log.warn(s"orig user list has ${users.size} elements, after deduping its ${deduped.size}")
    }
    deduped
  }

  def importFriends(friendsWithRawJson: Seq[(SocialUserInfo, JsValue)]): Seq[SocialUserRawInfo] = {
    val socialUserInfosNeedToUpdate: Seq[SocialUserInfoWithJsValue] = db.readOnly { implicit s =>
      friendsWithRawJson flatMap { case (f, json) => getIfUpdateNeeded(f) map { u => SocialUserInfoWithJsValue(u, json) } }
    }
    val dedupedToPersist = dedup(socialUserInfosNeedToUpdate)
    val socialUserInfos: Seq[SocialUserInfoWithJsValue] = dedupedToPersist.grouped(10).toSeq.flatMap { friendsWithJson =>
      try {
        db.readWrite { implicit s =>
          friendsWithJson.map { case u => u.copy(userInfo = repo.save(u.userInfo)) }
        }
      } catch {
        case e: Exception =>
          airbrake.notify(s"Error persisting a social user info. Killed a batch of ${friendsWithJson.size} saves out of ${dedupedToPersist.size} to save. " +
            s"Overall in this batch are ${friendsWithRawJson.size} friendsWithRawJson", e)
          Nil
      }
    }

    val socialUserRawInfos = socialUserInfos map { u => createSocialUserRawInfo(u.userInfo, u.json) }

    socialUserRawInfos foreach { info =>
      log.info(s"Adding user ${info.fullName} (${info.socialUserInfoId.get}) to S3")
      store += (info.socialUserInfoId.get -> info)
    }

    log.info(s"Imported ${socialUserRawInfos.size} friends")

    socialUserRawInfos
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
