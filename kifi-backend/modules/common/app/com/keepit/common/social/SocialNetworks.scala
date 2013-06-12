package com.keepit.common.social

case class SocialId(id: String) {
  override def toString = id
}

sealed abstract class SocialNetworkType(val name: String) {
  override def toString: String = name
}

object SocialNetworkType {
  def apply(s: String): SocialNetworkType =
    SocialNetworks.ALL.find(_.name == s.toLowerCase.trim)
      .getOrElse(throw new IllegalArgumentException(s"Invalid social network $s"))

  def unapply(snt: SocialNetworkType): Option[String] = Some(snt.name)
}

object SocialNetworks {
  val ALL = Set(FACEBOOK, LINKEDIN)
  case object LINKEDIN extends SocialNetworkType("linkedin")
  case object FACEBOOK extends SocialNetworkType("facebook")
}
