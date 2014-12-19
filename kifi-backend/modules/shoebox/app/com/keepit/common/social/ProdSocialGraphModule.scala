package com.keepit.common.social

import net.codingwell.scalaguice.ScalaMultibinder
import com.keepit.inject.AppScoped
import com.keepit.social.{ SocialGraphPlugin, SocialGraphModule, SocialGraph }

case class ProdSocialGraphModule() extends SocialGraphModule {
  def configure() {
    bind[SocialGraphPlugin].to[SocialGraphPluginImpl].in[AppScoped]
    bind[TwitterSocialGraph].to[TwitterSocialGraphImpl]
    val socialGraphBinder = ScalaMultibinder.newSetBinder[SocialGraph](binder)
    socialGraphBinder.addBinding.to[FacebookSocialGraph]
    socialGraphBinder.addBinding.to[TwitterSocialGraph]
    socialGraphBinder.addBinding.to[LinkedInSocialGraph]
  }
}
