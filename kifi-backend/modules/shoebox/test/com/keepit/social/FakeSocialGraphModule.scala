package com.keepit.common.social

import com.keepit.common.api.{ FakeUriShortner, UriShortener }
import com.keepit.inject.AppScoped
import com.keepit.model.SocialUserInfo
import scala.concurrent._
import com.keepit.social.{ SocialGraphPlugin, SocialGraphModule }

case class FakeSocialGraphModule() extends SocialGraphModule {
  def configure() {
    bindAllSocialGraphs(binder)
    bind[SocialGraphPlugin].to[FakeSocialGraphPlugin].in[AppScoped]
    bind[UriShortener].to[FakeUriShortner]
  }
}

class FakeSocialGraphPlugin extends SocialGraphPlugin {
  def asyncFetch(socialUserInfo: SocialUserInfo, broadcastToOthers: Boolean = true): Future[Unit] =
    Promise.successful(()).future
  def asyncRevokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] =
    Promise.successful(()).future
}
