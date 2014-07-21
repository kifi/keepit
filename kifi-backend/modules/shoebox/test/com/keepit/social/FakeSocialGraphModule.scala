package com.keepit.common.social

import com.keepit.model.SocialUserInfo
import scala.concurrent._
import com.keepit.model.SocialConnection
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.social.{ SocialGraphPlugin, SocialGraphModule }

case class FakeSocialGraphModule() extends SocialGraphModule {

  def configure() {
    bind[SocialGraphPlugin].to[FakeSocialGraphPlugin]
  }

}

class FakeSocialGraphPlugin extends SocialGraphPlugin {
  def asyncFetch(socialUserInfo: SocialUserInfo, broadcastToOthers: Boolean = true): Future[Unit] =
    Promise.successful().future
  def asyncRevokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] =
    Promise.successful().future
}
