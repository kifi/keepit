package com.keepit.social

import com.keepit.common.db.Id
import com.keepit.model.User

import securesocial.core.{ Identity, SocialUser }

case class UserIdentity(
    userId: Option[Id[User]],
    socialUser: SocialUser,
    allowSignup: Boolean = false,
    isComplete: Boolean = true) extends Identity {
  def identityId = socialUser.identityId
  def firstName = socialUser.firstName
  def lastName = socialUser.lastName
  def fullName = socialUser.fullName
  def email = socialUser.email
  def avatarUrl = socialUser.avatarUrl
  def authMethod = socialUser.authMethod
  def oAuth1Info = socialUser.oAuth1Info
  def oAuth2Info = socialUser.oAuth2Info
  def passwordInfo = socialUser.passwordInfo
}
