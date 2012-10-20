package com.keepit.common.social

case class SocialId(id: String)

sealed abstract class SocialNetworkType(val name: String) 

object SocialNetworks {
  case object FACEBOOK extends SocialNetworkType("facebook")
}
