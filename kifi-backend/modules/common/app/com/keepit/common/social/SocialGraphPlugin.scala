package com.keepit.common.social

import scala.concurrent.Future
import com.keepit.model._
import play.api.Plugin

trait SocialGraphPlugin extends Plugin {
  def asyncFetch(socialUserInfo: SocialUserInfo): Future[Seq[SocialConnection]]
  def asyncRevokePermissions(socialUserInfo: SocialUserInfo): Future[Unit]
}
