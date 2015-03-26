package com.keepit.common.social

import com.keepit.common.api.{ FakeUriShortner, UriShortener }
import com.keepit.model.SocialUserInfo
import net.codingwell.scalaguice.ScalaMultibinder
import scala.concurrent._
import com.keepit.model.SocialConnection
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.social.{ FakeTwitterSocialGraph, SocialGraph, SocialGraphPlugin, SocialGraphModule }

case class FakeSocialGraphModule() extends SocialGraphModule {

  def configure() {
    bind[SocialGraphPlugin].to[FakeSocialGraphPlugin]
    bind[TwitterSocialGraph].to[FakeTwitterSocialGraph]
    bind[UriShortener].to[FakeUriShortner]
  }

}

class FakeSocialGraphPlugin extends SocialGraphPlugin {
  def asyncFetch(socialUserInfo: SocialUserInfo, broadcastToOthers: Boolean = true): Future[Unit] =
    Promise.successful(()).future
  def asyncRevokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] =
    Promise.successful(()).future
}
