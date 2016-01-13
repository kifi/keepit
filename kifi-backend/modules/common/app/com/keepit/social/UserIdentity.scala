package com.keepit.social

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.EmailAddress
import com.keepit.model.view.UserSessionView
import com.keepit.model.{ SocialUserInfo, UserCred, User }
import com.keepit.slack.models.{ SlackAccessToken, SlackUserId, SlackTeamId }
import play.api.libs.functional.syntax._
import play.api.libs.json._

import securesocial.core._

import scala.concurrent.duration.Duration
import scala.util.{ Failure, Try }

sealed abstract class MaybeUserIdentity(val userId: Option[Id[User]], val socialUser: SocialUser) extends SocialUser(
  socialUser.identityId,
  socialUser.firstName,
  socialUser.lastName,
  socialUser.fullName,
  socialUser.email,
  socialUser.avatarUrl,
  socialUser.authMethod,
  socialUser.oAuth1Info,
  socialUser.oAuth2Info,
  socialUser.passwordInfo
)

// todo(Léo): can we make userId not optional in this case?
class UserIdentity(userId: Option[Id[User]], socialUser: SocialUser) extends MaybeUserIdentity(userId, socialUser)

object UserIdentity {
  def apply(userId: Option[Id[User]], socialUser: SocialUser) = new UserIdentity(userId, socialUser)
  def unapply(u: UserIdentity) = Some(u.userId, u.socialUser)

  def apply(user: User, emailAddress: EmailAddress, cred: Option[UserCred]): UserIdentity = {
    val passwordInfo = cred.map(actualCred => PasswordInfo(hasher = "bcrypt", password = actualCred.credentials))
    val socialUser = SocialUser(
      identityId = IdentityHelpers.toIdentityId(emailAddress),
      firstName = user.firstName,
      lastName = user.lastName,
      fullName = user.fullName,
      email = Some(emailAddress.address),
      avatarUrl = None,
      authMethod = AuthenticationMethod.UserPassword,
      passwordInfo = passwordInfo
    )
    UserIdentity(user.id, socialUser)
  }

  def apply(user: User, slackTeamId: SlackTeamId, slackUserId: SlackUserId, tokenOpt: Option[SlackAccessToken]): UserIdentity = {
    val socialUser = SocialUser(
      identityId = IdentityHelpers.toIdentityId(slackTeamId, slackUserId),
      firstName = user.firstName,
      lastName = user.lastName,
      fullName = user.fullName,
      email = None,
      avatarUrl = None,
      authMethod = AuthenticationMethod.OAuth2,
      oAuth2Info = tokenOpt.map(t => OAuth2Info(t.token, None, None, None))
    )
    UserIdentity(user.id, socialUser)
  }

  import com.keepit.serializer.SocialUserSerializer._
  implicit val format = (
    (__ \ 'userId).formatNullable(Id.format[User]) and
    (__ \ 'socialUser).format[SocialUser]
  )(UserIdentity.apply, unlift(UserIdentity.unapply))
}

case class UserIdentityIdentityIdKey(id: IdentityId) extends Key[UserIdentity] {
  override val version = 1
  val namespace = "user_identity_by_identity_id"
  def toKey(): String = id.providerId + "_" + id.userId
}

object UserIdentityIdentityIdKey {
  def apply(networkType: SocialNetworkType, socialId: SocialId): UserIdentityIdentityIdKey = UserIdentityIdentityIdKey(IdentityHelpers.toIdentityId(networkType, socialId))
  def apply(emailAddress: EmailAddress): UserIdentityIdentityIdKey = UserIdentityIdentityIdKey(IdentityHelpers.toIdentityId(emailAddress))
}

class UserIdentityCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserIdentityIdentityIdKey, UserIdentity](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

class NewUserIdentity(userId: Option[Id[User]], socialUser: SocialUser) extends MaybeUserIdentity(userId, socialUser)

object NewUserIdentity {
  def apply(userId: Option[Id[User]], socialUser: SocialUser) = new NewUserIdentity(userId, socialUser)
  def unapply(u: NewUserIdentity) = Some(u.userId, u.socialUser)
}

object IdentityHelpers {
  def parseNetworkType(identityId: IdentityId): SocialNetworkType = SocialNetworkType(identityId.providerId)
  def parseNetworkType(identity: Identity): SocialNetworkType = parseNetworkType(identity.identityId)

  def toIdentityId(networkType: SocialNetworkType, socialId: SocialId): IdentityId = IdentityId(userId = socialId.id, providerId = networkType.authProvider)
  def parseSocialId(identityId: IdentityId): SocialId = identityId.userId.trim match {
    case socialId if socialId.nonEmpty => SocialId(socialId)
    case _ => throw new IllegalArgumentException(s"Invalid social id from IdentityId: $identityId")
  }
  def parseSocialId(identity: Identity): SocialId = parseSocialId(identity.identityId)

  def toIdentityId(emailAddress: EmailAddress): IdentityId = IdentityId(userId = emailAddress.address, providerId = SocialNetworks.EMAIL.authProvider)
  def parseEmailAddress(identity: Identity): Try[EmailAddress] = identity.email match {
    case None => Failure(new IllegalArgumentException(s"Email address not found in $identity"))
    case Some(address) => EmailAddress.validate(address)
  }

  def toIdentityId(teamId: SlackTeamId, userId: SlackUserId): IdentityId = IdentityId(userId = s"${teamId.value}|${userId.value}", providerId = SocialNetworks.SLACK.authProvider)
  def parseSlackId(identityId: IdentityId): (SlackTeamId, SlackUserId) = identityId.userId.trim.split("|").toSeq.filter(_.nonEmpty) match {
    case Seq(teamIdStr, userIdStr) => (SlackTeamId(teamIdStr), SlackUserId(userIdStr))
    case _ => throw new IllegalArgumentException(s"Invalid Slack credentials from IdentityId: $identityId")
  }

  def getIdentityId(session: UserSessionView): IdentityId = IdentityId(session.socialId.id, session.provider.name)
}
