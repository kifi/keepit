package com.keepit.socialtypeahead.socialusers

import com.keepit.common.db.Id
import com.keepit.social.{SocialId, SocialNetworks, SocialNetworkType}
import com.keepit.common.cache.{CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl, Key}
import com.keepit.common.logging.AccessLog
import com.keepit.model.SocialUserInfo
import scala.concurrent.duration.Duration
import play.api.libs.json._

object SocialUserName {
   // This is an intentionally compact representation to support storing large social graphs
  implicit val format = Format[SocialUserName](
    __.read[Seq[JsValue]].map {
      case Seq(JsNumber(id), JsNumber(joined), JsString(fullName), JsString(pictureUrl), JsString(socialId), JsString(networkType)) =>
        SocialUserName(
          Id[SocialUserInfo](id.toLong),
          (joined == 1),
          fullName,
          Some(pictureUrl).filterNot(_.isEmpty),
          SocialId(socialId),
          SocialNetworkType(networkType))
    },
    new Writes[SocialUserName] {
      def writes(o: SocialUserName): JsValue =
        Json.arr(o.id.id, (if (o.joined) 1 else 0), o.fullName, o.pictureUrl.getOrElse[String](""), o.socialId.id, o.networkType.name)
    })

  def fromSocialUser(sui: SocialUserInfo): SocialUserName = SocialUserName(sui.id.get, sui.userId.isDefined, sui.fullName, sui.pictureUrl, sui.socialId, sui.networkType)
}

case class SocialUserName(
  id: Id[SocialUserInfo],
  joined: Boolean,
  fullName: String,
  pictureUrl: Option[String],
  socialId: SocialId,
  networkType: SocialNetworkType) {

  def getPictureUrl(preferredWidth: Int = 50, preferredHeight: Int = 50): Option[String] = networkType match {
    case SocialNetworks.FACEBOOK =>
      Some(s"https://graph.facebook.com/$socialId/picture?width=$preferredWidth&height=$preferredHeight")
    case _ => pictureUrl
  }
}

case class SocialUserNameKey(id: Id[SocialUserName]) extends Key[SocialUserName] {
  val namespace = "social_user_name"
  override val version = 1
  def toKey(): String = id.id.toString
}

class SocialUserNameCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[SocialUserNameKey, SocialUserName](stats, accessLog, inner, outer: _*)

