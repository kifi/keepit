package com.keepit.common.social

sealed abstract class SocialNetworkType(val name: String) 

object SocialNetworks {
  case object FACEBOOK extends SocialNetworkType("facebook")
}
