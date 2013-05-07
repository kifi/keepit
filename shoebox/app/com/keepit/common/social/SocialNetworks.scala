package com.keepit.common.social

import com.keepit.common.social.SocialNetworks.FACEBOOK

case class SocialId(id: String) {
  override def toString = id
}

sealed abstract class SocialNetworkType(val name: String)

object SocialNetworkType {
  def apply(s: String): SocialNetworkType = s.toLowerCase.trim match {
    case FACEBOOK.name => FACEBOOK
    case t => throw new IllegalArgumentException("Invalid social network $t")
  }
  def unapply(snt: SocialNetworkType): Option[String] = Some(snt.name)
}

object SocialNetworks {
  case object FACEBOOK extends SocialNetworkType("facebook")
}
