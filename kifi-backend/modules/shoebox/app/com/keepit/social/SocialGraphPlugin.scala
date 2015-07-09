package com.keepit.social

import scala.concurrent.Future
import com.keepit.model._
import play.api.Plugin

trait SocialGraphPlugin extends Plugin {
  def asyncFetch(socialUserInfo: SocialUserInfo, broadcastToOthers: Boolean = true): Future[Unit]
  def asyncRevokePermissions(socialUserInfo: SocialUserInfo): Future[Unit]
}
