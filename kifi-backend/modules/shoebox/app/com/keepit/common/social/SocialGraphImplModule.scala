package com.keepit.common.social

import net.codingwell.scalaguice.ScalaMultibinder
import com.keepit.inject.AppScoped

case class SocialGraphImplModule() extends SocialGraphModule {
  def configure {
    bind[SocialGraphRefresher].to[SocialGraphRefresherImpl].in[AppScoped]
    bind[SocialGraphPlugin].to[SocialGraphPluginImpl].in[AppScoped]
    val socialGraphBinder = ScalaMultibinder.newSetBinder[SocialGraph](binder)
    socialGraphBinder.addBinding.to[FacebookSocialGraph]
    socialGraphBinder.addBinding.to[LinkedInSocialGraph]
    bind[ConnectionUpdater].to[UserConnectionCreator]
  }
}
