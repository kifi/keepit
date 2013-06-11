package com.keepit.common.social

import com.keepit.model.SocialUserInfo

import play.api.libs.json.JsValue

/**
 * A generic social graph trait for a particular social network, e.g. Facebook, Twitter, LinkedIn
 */
trait SocialGraph {
  val networkType: SocialNetworkType
  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo]
  def extractEmails(parentJson: JsValue): Seq[String]
  def extractFriends(parentJson: JsValue): Seq[(SocialUserInfo, JsValue)]
}
