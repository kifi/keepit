package com.keepit.common.social

import com.keepit.inject.AppScoped
import com.keepit.social.{ SocialGraphPlugin, SocialGraphModule }

case class ProdSocialGraphModule() extends SocialGraphModule {
  def configure() {
    bindAllSocialGraphs(binder)
    bind[SocialGraphPlugin].to[SocialGraphPluginImpl].in[AppScoped]
  }
}
