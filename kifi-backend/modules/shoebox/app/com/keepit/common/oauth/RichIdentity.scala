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

case class SlackIdentity(teamId: SlackTeamId, userId: SlackUserId, user: Option[SlackUserInfo], tokenWithScopes: Option[SlackTokenWithScopes]) extends RichIdentity
object SlackIdentity {
  def apply(auth: SlackAuthorizationResponse, identity: SlackIdentifyResponse, fullUser: Option[FullSlackUserInfo]): SlackIdentity = {
    require(auth.teamId == identity.teamId && !fullUser.exists(_.id != identity.userId))
    SlackIdentity(
      identity.teamId,
      identity.userId,
      fullUser,
      Some(SlackTokenWithScopes(auth.accessToken, auth.scopes))
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
      firstName = slackIdentity.user.flatMap(_.firstName).getOrElse(""),
      lastName = slackIdentity.user.flatMap(_.lastName).getOrElse(""),
      fullName = slackIdentity.user.flatMap(_.fullName).getOrElse(""),
      email = slackIdentity.user.map(_.emailAddress.get.address),
      avatarUrl = slackIdentity.user.flatMap(_.avatarUrl),
      authMethod = AuthenticationMethod.OAuth2,
      oAuth2Info = slackIdentity.tokenWithScopes.map(ts => (OAuth2Info(ts.token.token, None, None, None)))
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
