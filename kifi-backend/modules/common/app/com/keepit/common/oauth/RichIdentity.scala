package com.keepit.common.oauth

import com.keepit.common.mail.EmailAddress
import com.keepit.slack.models._
import com.keepit.social.IdentityHelpers
import securesocial.core.providers.utils.GravatarHelper
import securesocial.core._
import com.keepit.common.core._

sealed trait RichIdentity
case class FacebookIdentity(auth: OAuth2Info, profile: UserProfileInfo) extends RichIdentity
case class LinkedInIdentity(auth: OAuth2Info, profile: UserProfileInfo) extends RichIdentity
case class TwitterIdentity(auth: OAuth1Info, profile: UserProfileInfo) extends RichIdentity
case class EmailPasswordIdentity(firstName: String, lastName: String, email: EmailAddress, password: Option[PasswordInfo]) extends RichIdentity
case class SlackIdentity(teamId: SlackTeamId, teamName: SlackTeamName, userId: SlackUserId, username: SlackUsername, token: Option[SlackAccessToken], scopes: Set[SlackAuthScope], user: Option[SlackUserInfo]) extends RichIdentity

object RichIdentity {
  def toSocialUser(richIdentity: RichIdentity): SocialUser = richIdentity match {
    case FacebookIdentity(auth, profile) => makeSocialUser(auth, profile)
    case LinkedInIdentity(auth, profile) => makeSocialUser(auth, profile)
    case TwitterIdentity(auth, profile) => makeSocialUser(auth, profile)
    case slackIdentity: SlackIdentity => SocialUser(
      identityId = IdentityHelpers.toIdentityId(slackIdentity.teamId, slackIdentity.userId),
      firstName = slackIdentity.user.flatMap(_.profile.firstName).getOrElse(""),
      lastName = slackIdentity.user.flatMap(_.profile.lastName).getOrElse(""),
      fullName = slackIdentity.user.flatMap(_.profile.fullName).getOrElse(""),
      email = slackIdentity.user.map(_.profile.emailAddress.address),
      avatarUrl = slackIdentity.user.flatMap(_.profile.icon.maxByOpt(_._1).map(_._2)),
      authMethod = AuthenticationMethod.OAuth2,
      oAuth2Info = slackIdentity.token.map(t => (OAuth2Info(t.token, None, None, None)))
    )
    case EmailPasswordIdentity(firstName, lastName, email, password) =>
      SocialUser(
        identityId = IdentityHelpers.toIdentityId(email),
        firstName = firstName,
        lastName = lastName,
        fullName = s"$firstName $lastName",
        email = Some(email.address),
        avatarUrl = GravatarHelper.avatarFor(email.address),
        authMethod = AuthenticationMethod.UserPassword,
        passwordInfo = password
      )
  }

  private def makeSocialUser(auth: OAuth2Info, info: UserProfileInfo) = {
    SocialUser(
      identityId = IdentityId(info.userId.id, info.providerId.id),
      firstName = info.firstNameOpt getOrElse "",
      lastName = info.lastNameOpt getOrElse "",
      fullName = info.name,
      avatarUrl = info.pictureUrl.map(_.toString),
      email = info.emailOpt.map(_.address),
      authMethod = AuthenticationMethod.OAuth2,
      oAuth2Info = Some(auth)
    )
  }

  private def makeSocialUser(auth: OAuth1Info, info: UserProfileInfo) = {
    SocialUser(
      identityId = IdentityId(info.userId.id, info.providerId.id),
      firstName = info.firstNameOpt getOrElse "",
      lastName = info.lastNameOpt getOrElse "",
      fullName = info.name,
      avatarUrl = info.pictureUrl.map(_.toString),
      email = info.emailOpt.map(_.address),
      authMethod = AuthenticationMethod.OAuth1,
      oAuth1Info = Some(auth)
    )
  }
}
