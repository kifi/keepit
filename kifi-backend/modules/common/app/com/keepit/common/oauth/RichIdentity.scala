package com.keepit.common.oauth

import com.keepit.common.mail.EmailAddress
import com.keepit.slack.models._
import com.keepit.social.IdentityHelpers
import securesocial.core._
import securesocial.core.providers.utils.GravatarHelper

sealed trait RichIdentity

case class FacebookIdentity(socialUser: SocialUser) extends RichIdentity
case class LinkedInIdentity(socialUser: SocialUser) extends RichIdentity
case class TwitterIdentity(socialUser: SocialUser, pictureUrl: Option[String], profileUrl: Option[String]) extends RichIdentity
case class EmailPasswordIdentity(firstName: String, lastName: String, email: EmailAddress, password: Option[PasswordInfo]) extends RichIdentity

case class SlackIdentity(teamId: SlackTeamId, teamName: SlackTeamName, token: Option[SlackUserAccessToken], scopes: Set[SlackAuthScope], userId: SlackUserId, username: SlackUsername, user: Option[SlackUserInfo]) extends RichIdentity
object SlackIdentity {
  def apply(auth: SlackAuthorizationResponse, identity: SlackIdentifyResponse, user: Option[SlackUserInfo]): SlackIdentity = {
    require(auth.teamId == identity.teamId && auth.teamName == identity.teamName && !user.exists(_.id != identity.userId))
    SlackIdentity(
      auth.teamId,
      auth.teamName,
      Some(auth.accessToken),
      auth.scopes,
      identity.userId,
      identity.userName,
      user
    )
  }
}

object RichIdentity {
  def toSocialUser(richIdentity: RichIdentity): SocialUser = richIdentity match {
    case FacebookIdentity(socialUser) => socialUser
    case LinkedInIdentity(socialUser) => socialUser
    case TwitterIdentity(socialUser, _, _) => socialUser
    case slackIdentity: SlackIdentity => SocialUser(
      identityId = IdentityHelpers.toIdentityId(slackIdentity.teamId, slackIdentity.userId),
      firstName = slackIdentity.user.flatMap(_.profile.firstName).getOrElse(slackIdentity.username.value),
      lastName = slackIdentity.user.flatMap(_.profile.lastName).getOrElse(""),
      fullName = slackIdentity.user.flatMap(_.profile.fullName).orElse(slackIdentity.user.flatMap(_.profile.firstName)).getOrElse(slackIdentity.username.value),
      email = slackIdentity.user.map(_.profile.emailAddress.get.address),
      avatarUrl = slackIdentity.user.flatMap(_.profile.avatarUrl),
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

  // todo(Léo): this is kind of backward, perhaps IdentityId should be required on RichIdentity
  def toIdentityId(richIdentity: RichIdentity): IdentityId = toSocialUser(richIdentity).identityId
}
