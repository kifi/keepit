package com.keepit.social

import org.joda.time.DateTime
import org.specs2.mutable._

import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.social.{SocialNetworks, SocialId}
import com.keepit.inject.inject
import com.keepit.model.{User, SocialUserInfo, UserSession}
import com.keepit.test.{FakeClock, DbRepos, EmptyApplication}

import play.api.Play.current
import play.api.test.Helpers._
import securesocial.core.{Authenticator, UserId}

class SecureSocialAuthenticatorPluginTest extends Specification with DbRepos {
  def healthcheckPlugin = inject[HealthcheckPlugin]
  "SecureSocialAuthenticatorPlugin" should {
    "find existing user sessions" in {
      running(new EmptyApplication()) {
        val plugin =
          new SecureSocialAuthenticatorPlugin(db, socialUserInfoRepo, userSessionRepo, healthcheckPlugin, current)
        val id = ExternalId[UserSession]()
        plugin.find(id.id) === Right(None)
        db.readWrite { implicit s =>
          userSessionRepo.save(UserSession(
            externalId = id, socialId = SocialId("gm"), provider = SocialNetworks.FACEBOOK,
            expires = new DateTime("2020-10-20")))
        }
        val authenticator = plugin.find(id.id).right.get.get
        authenticator.expired === false
        authenticator.expirationDate.getMillis === new DateTime("2020-10-20").getMillis
        authenticator.userId === UserId("gm", "facebook")
      }
    }
    "not find deleted sessions" in {
      running(new EmptyApplication()) {
        val plugin =
          new SecureSocialAuthenticatorPlugin(db, socialUserInfoRepo, userSessionRepo, healthcheckPlugin, current)
        val id = ExternalId[UserSession]()
        db.readWrite { implicit s =>
          userSessionRepo.save(UserSession(
            externalId = id, socialId = SocialId("gm"), provider = SocialNetworks.FACEBOOK,
            expires = new DateTime("2020-10-20")))
        }
        val authenticator = plugin.find(id.id).right.get.get
        authenticator.expired === false
        authenticator.expirationDate.getMillis === new DateTime("2020-10-20").getMillis
        authenticator.userId === UserId("gm", "facebook")
        plugin.delete(id.id)
        plugin.find(id.id) === Right(None)
      }
    }
    "not get expired sessions" in {
      running(new EmptyApplication()) {
        inject[FakeClock].push(new DateTime("2015-01-01"))

        val plugin =
          new SecureSocialAuthenticatorPlugin(db, socialUserInfoRepo, userSessionRepo, healthcheckPlugin, current)
        val id = ExternalId[UserSession]()
        db.readWrite { implicit s =>
          userSessionRepo.save(UserSession(
            externalId = id, socialId = SocialId("gm"), provider = SocialNetworks.FACEBOOK,
            expires = new DateTime("2010-10-20")))
        }
        plugin.find(id.id).right.get === None
      }
    }
    "associate with the correct user" in {
      running(new EmptyApplication()) {
        val plugin =
          new SecureSocialAuthenticatorPlugin(db, socialUserInfoRepo, userSessionRepo, healthcheckPlugin, current)
        val id = ExternalId[UserSession]()
        val socialId = SocialId("gm")
        val provider = SocialNetworks.FACEBOOK
        val user = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Greg", lastName = "Methvin"))
          socialUserInfoRepo.save(SocialUserInfo(
            userId = user.id, socialId = socialId, fullName = "Greg Methvin", networkType = provider))
          user
        }
        plugin.save(Authenticator(id.id, UserId(socialId.id, provider.name),
          new DateTime, new DateTime, new DateTime("2015-10-20")))
        val authenticator = plugin.find(id.id).right.get.get
        authenticator.userId.id === socialId.id
        authenticator.userId.providerId === provider.name
        db.readOnly { implicit s =>
          userSessionRepo.get(id).userId.get === user.id.get
        }
      }
    }
  }
}
