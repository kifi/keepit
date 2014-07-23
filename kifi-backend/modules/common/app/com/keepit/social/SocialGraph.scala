package com.keepit.social

import scala.concurrent.Future
import scala.util.Try

import com.keepit.model.{ UserValueName, SocialUserInfo }

import net.codingwell.scalaguice.ScalaModule

import play.api.libs.json.JsValue

import securesocial.core.{ IdentityId, OAuth2Settings }
import com.keepit.common.mail.EmailAddress

/**
 * A generic social graph trait for a particular social network, e.g. Facebook, Twitter, LinkedIn
 */
trait SocialGraph {
  val networkType: SocialNetworkType
  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo]
  def extractEmails(parentJson: JsValue): Seq[EmailAddress]
  def extractFriends(parentJson: JsValue): Seq[SocialUserInfo]
  def updateSocialUserInfo(sui: SocialUserInfo, json: JsValue): SocialUserInfo
  def revokePermissions(socialUserInfo: SocialUserInfo): Future[Unit]
  def extractUserValues(json: JsValue): Map[UserValueName, String]
  def vetJsAccessToken(settings: OAuth2Settings, json: JsValue): Try[IdentityId]
}

trait SocialGraphModule extends ScalaModule
