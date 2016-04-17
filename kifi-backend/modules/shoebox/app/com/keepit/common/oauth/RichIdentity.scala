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

case class SlackIdentity(teamId: SlackTeamId, userId: SlackUserId, team: Option[SlackTeamInfo], user: Option[SlackUserInfo], tokenWithScopes: Option[SlackTokenWithScopes]) extends RichIdentity
object SlackIdentity {
  def apply(auth: SlackAppAuthorizationResponse, identity: SlackIdentifyResponse, fullUser: Option[FullSlackUserInfo]): SlackIdentity = {
    require(auth.teamId == identity.teamId && fullUser.forall(_.id == identity.userId))
    SlackIdentity(
      identity.teamId,
      identity.userId,
      Some(BasicSlackTeamInfo(auth.teamId, auth.teamName)),
      fullUser,
      Some(SlackTokenWithScopes(auth.accessToken, auth.scopes))
    )
  }

  // todo(Léo): making SlackUserIdentityResponse optional could save a call to Slack in some scenarios that we don't have
  def apply(auth: SlackIdentityAuthorizationResponse, identity: SlackUserIdentityResponse, fullUser: Option[FullSlackUserInfo]): SlackIdentity = {
    require(fullUser.forall(_.id == auth.userId) && identity.user.id == auth.userId && identity.team.forall(_.id == auth.teamId))
    SlackIdentity(
      auth.teamId,
      auth.userId,
      identity.team,
      fullUser orElse Some(identity.user),
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
