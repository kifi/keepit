package com.keepit.social

import com.keepit.common.logging.Logging
import com.keepit.common.store.ImageSize
import com.keepit.social.SocialNetworks.FORTYTWO
import play.api.libs.json._
import play.api.libs.json.JsString
import securesocial.core.providers.utils.GravatarHelper

case class SocialId(id: String) extends Logging {
  override def toString = id
  if (id.trim.isEmpty) log.error("Social Id Is broken", new Exception("Social Id Is broken"))
}

object SocialId {
  implicit val format: Format[SocialId] = Format(Reads.of[String].map(SocialId.apply), Writes(socialId => JsString(socialId.id)))
}

sealed abstract class SocialNetworkType(val name: String, val displayName: String, val authProvider: String) {
  override def toString: String = name
}

object SocialNetworkType {
  def apply(s: String): SocialNetworkType = {
    val trimmed = s.toLowerCase.trim
    SocialNetworks.ALL.find(_.name == trimmed).getOrElse(
      if ("userpass" == trimmed) FORTYTWO /* hack -- REMOVEME */
      else throw new IllegalArgumentException(s"Invalid social network ($s)")
    )
  }

  def unapply(snt: SocialNetworkType): Option[String] = Some(snt.name)

  implicit val format: Format[SocialNetworkType] = Format(Reads.of[String].map(SocialNetworkType.apply), Writes(socialNetwork => JsString(socialNetwork.name)))
}

object SocialNetworks {
  val SUPPORTED = Seq(FACEBOOK, TWITTER, LINKEDIN)
  val ALL = Seq(FACEBOOK, TWITTER, LINKEDIN, FORTYTWO, EMAIL)
  val REFRESHING = Seq(FACEBOOK, TWITTER, LINKEDIN)
  val verifiedEmailProviders: Set[SocialNetworkType] = Set(FACEBOOK, TWITTER, LINKEDIN)
  val social: Set[SocialNetworkType] = Set(FACEBOOK, TWITTER, LINKEDIN)
  case object LINKEDIN extends SocialNetworkType("linkedin", "LinkedIn", "linkedin")
  case object FACEBOOK extends SocialNetworkType("facebook", "Facebook", "facebook")
  case object TWITTER extends SocialNetworkType("twitter", "Twitter", "twitter")
  case object FORTYTWO extends SocialNetworkType("fortytwo", "FortyTwo", "userpass") // hack -- userpass is overloaded with secure social provider -- should be separated
  case object FORTYTWO_NF extends SocialNetworkType("fortytwoNF", "FortyTwoNF", "userpass") // this hack is getting worse
  case object EMAIL extends SocialNetworkType("email", "Email", "userpass") // hack -- userpass is overloaded with secure social provider -- should be separated

  def getPictureUrl(networkType: SocialNetworkType, socialId: SocialId)(preferredSize: Option[ImageSize]): Option[String] = networkType match {
    case FACEBOOK => {
      val requestedSize = preferredSize getOrElse ImageSize(1000, 1000)
      Some(s"https://graph.facebook.com/v2.0/$socialId/picture?width=${requestedSize.width}&height=${requestedSize.height}")
    }
    case EMAIL | FORTYTWO | FORTYTWO_NF => GravatarHelper.avatarFor(socialId.id)
    case _ => None
  }

  def getProfileUrl(networkType: SocialNetworkType, socialId: SocialId): Option[String] = networkType match {
    case SocialNetworks.FACEBOOK => Some(s"https://www.facebook.com/$socialId")
    case _ => None
  }
}
