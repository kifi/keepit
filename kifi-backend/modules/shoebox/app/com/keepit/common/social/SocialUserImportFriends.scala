package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
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
    val socialUserInfosNeedToUpdate = db.readOnlyReplica { implicit s =>
      deDuped.flatMap {
        case friend =>
          getIfUpdateNeeded(friend)
      }
    }
    log.info(s"Importing ${socialUserInfo.userId} (${socialUserInfo.fullName})'s friends.")

    val socialUserInfos = db.readWriteBatch(socialUserInfosNeedToUpdate, attempts = 3) {
      case (session, friend) =>
        val existingOpt = repo.getOpt(friend.socialId, friend.networkType)(session)
        existingOpt.map { existing =>
          //updating social user if its not up to date
          if (existing.username != friend.username || existing.profileUrl != friend.profileUrl || existing.pictureUrl != friend.pictureUrl) {
            repo.save(existing.copy(username = friend.username, profileUrl = friend.profileUrl, pictureUrl = friend.pictureUrl))(session)
          } else existing
        }.orElse {
          try {
            Some(repo.save(friend)(session))
          } catch {
            case e: Exception =>
              repo.deleteCache(friend)(session)
              airbrake.notify(s"Error persisting single social user info for userId ${socialUserInfo.userId} (${socialUserInfo.fullName}), friend social id ${friend.socialId}, network ${friend.networkType}", e)
              fixSocialUser(friend)(session)
              None
          }
        }
    }.values.toList.map { v =>
      v.toOption.flatten
    }.flatten

    log.info(s"Imported ${socialUserInfos.size} friends")
    socialUserInfos
  }

  def fixSocialUser(friend: SocialUserInfo)(implicit session: RWSession): Unit = try {
    repo.getByUsernameOpt(friend.username.get, friend.networkType) map { existing =>
      //got a fresh social user that has an existing social user that claimed its social username.
      //looking at the data its probably wrong and the new one is good. https://fortytwo.airbrake.io/projects/91268/groups/1081513854301130421/notices/1576396721757049802
      repo.save(existing.copy(username = None, profileUrl = None, pictureUrl = None))
      repo.save(friend)
    }
  } catch {
    case e: Exception =>
      airbrake.notify(s"Error persisting fixup for social user info for userId ${friend.userId} (${friend.fullName}), network ${friend.networkType}", e)
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
