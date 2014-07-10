package com.keepit.controllers.core

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.performance.timing
import com.keepit.common.social._
import com.keepit.social.{ SocialNetworkType, SocialId }
import com.keepit.model._

import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.logging.Logging

case class NetworkInfo(profileUrl: Option[String], connected: Boolean)

object NetworkInfo {
  implicit val writesNetworkInfo = Json.writes[NetworkInfo]
  implicit val writesNetworkTypeToNetworkInfo = new Writes[Map[SocialNetworkType, NetworkInfo]] {
    def writes(o: Map[SocialNetworkType, NetworkInfo]): JsValue =
      JsObject(o.map { case (network, info) => network.name -> Json.toJson(info) }.toSeq)
  }
}

@Singleton
class NetworkInfoLoader @Inject() (
    db: Database,
    userRepo: UserRepo,
    socialConnectionRepo: SocialConnectionRepo,
    socialUserInfoRepo: SocialUserInfoRepo) extends Logging {

  private def load(mySocialUsers: Seq[SocialUserInfo], friendId: Id[User]): Map[SocialNetworkType, NetworkInfo] = timing(s"loadNetworkInfo friendId($friendId) mySocialUsers:(len=${mySocialUsers.length})") {
    db.readOnlyMaster { implicit s =>
      for (su <- socialUserInfoRepo.getByUser(friendId)) yield {
        su.networkType -> NetworkInfo(
          profileUrl = su.getProfileUrl,
          connected = mySocialUsers.exists { mySu =>
            mySu.networkType == su.networkType &&
              socialConnectionRepo.getConnectionOpt(su.id.get, mySu.id.get).isDefined
          }
        )
      }
    }.toMap
  }

  def load(userId: Id[User], friendId: ExternalId[User]): Map[SocialNetworkType, NetworkInfo] = {
    db.readOnlyMaster { implicit s =>
      userRepo.getOpt(friendId).map(f => socialUserInfoRepo.getByUser(userId) -> f.id.get)
    } map { case (sus, fid) => load(sus, fid) } getOrElse Map.empty
  }
}
