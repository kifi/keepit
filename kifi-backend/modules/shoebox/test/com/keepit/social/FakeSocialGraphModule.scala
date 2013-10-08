package com.keepit.common.social

import com.keepit.model.SocialUserInfo
import scala.concurrent._
import com.keepit.model.SocialConnection
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.social.{SocialGraphPlugin, SocialGraphModule}

case class FakeSocialGraphModule() extends SocialGraphModule {

  def configure() {
    bind[SocialGraphPlugin].to[FakeSocialGraphPlugin]
  }

}

class FakeSocialGraphPlugin extends SocialGraphPlugin {
  def asyncFetch(socialUserInfo: SocialUserInfo): Future[Seq[SocialConnection]] =
    future { throw new Exception("Not Implemented") }
  def asyncRevokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] =
    future { throw new Exception("Not Implemented") }
}
