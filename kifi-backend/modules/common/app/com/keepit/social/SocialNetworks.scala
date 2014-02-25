package com.keepit.social

import com.keepit.social.SocialNetworks.FORTYTWO

case class SocialId(id: String) {
  override def toString = id
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
}

object SocialNetworks {
  val SUPPORTED = Seq(FACEBOOK, LINKEDIN)
  val ALL = Seq(FACEBOOK, LINKEDIN, FORTYTWO, EMAIL)
  val REFRESHING = Seq(FACEBOOK, LINKEDIN)
  case object LINKEDIN extends SocialNetworkType("linkedin", "LinkedIn", "linkedin")
  case object FACEBOOK extends SocialNetworkType("facebook", "Facebook", "facebook")
  case object FORTYTWO extends SocialNetworkType("fortytwo", "FortyTwo", "userpass") // hack -- userpass is overloaded with secure social provider -- should be separated
  case object EMAIL extends SocialNetworkType("email", "Email", "userpass") // hack -- userpass is overloaded with secure social provider -- should be separated
}
