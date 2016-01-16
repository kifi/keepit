package com.keepit.common.oauth

import com.keepit.common.mail.EmailAddress
import com.keepit.slack.models._
import com.keepit.social.IdentityHelpers
import securesocial.core.providers.utils.GravatarHelper
import securesocial.core.{ OAuth2Info, AuthenticationMethod, PasswordInfo, SocialUser }
import com.keepit.common.core._

sealed trait RichIdentity
case class FacebookIdentity(socialUser: SocialUser) extends RichIdentity
case class LinkedInIdentity(socialUser: SocialUser) extends RichIdentity
case class TwitterIdentity(socialUser: SocialUser) extends RichIdentity
case class EmailPasswordIdentity(firstName: String, lastName: String, email: EmailAddress, password: Option[PasswordInfo]) extends RichIdentity
case class SlackIdentity(teamId: SlackTeamId, teamName: SlackTeamName, userId: SlackUserId, username: SlackUsername, token: Option[SlackAccessToken], scopes: Set[SlackAuthScope], user: Option[SlackUserInfo]) extends RichIdentity

object RichIdentity {
  def toSocialUser(richIdentity: RichIdentity): SocialUser = richIdentity match {
    case FacebookIdentity(socialUser) => socialUser
    case LinkedInIdentity(socialUser) => socialUser
    case TwitterIdentity(socialUser) => socialUser
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
}
