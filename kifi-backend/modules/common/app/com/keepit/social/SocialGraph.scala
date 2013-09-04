package com.keepit.social

import scala.concurrent.Future

import com.keepit.model.SocialUserInfo

import play.api.libs.json.JsValue
import net.codingwell.scalaguice.ScalaModule

/**
 * A generic social graph trait for a particular social network, e.g. Facebook, Twitter, LinkedIn
 */
trait SocialGraph {
  val networkType: SocialNetworkType
  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo]
  def extractEmails(parentJson: JsValue): Seq[String]
  def extractFriends(parentJson: JsValue): Seq[(SocialUserInfo, JsValue)]
  def updateSocialUserInfo(sui: SocialUserInfo, json: JsValue): SocialUserInfo
  def revokePermissions(socialUserInfo: SocialUserInfo): Future[Unit]
}

trait SocialGraphModule extends ScalaModule
