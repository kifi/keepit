package com.keepit.social

import org.joda.time.DateTime
import org.specs2.mutable._

import com.keepit.common.db.{ FakeSlickSessionProvider, ExternalId }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ Username, User, SocialUserInfo, UserSession }
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }

import play.api.Play.current
import play.api.test.Helpers._
import securesocial.core.{ Authenticator, IdentityId }
import com.keepit.common.time.FakeClock
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

class SecureSocialAuthenticatorPluginTest extends Specification with ShoeboxApplicationInjector {
  def airbrake = inject[AirbrakeNotifier]
  "SecureSocialAuthenticatorPlugin" should {
    "find existing user sessions" in {
      running(new ShoeboxApplication()) {
        val plugin = new SecureSocialAuthenticatorPluginImpl(db, socialUserInfoRepo, userSessionRepo, airbrake, current)
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
        authenticator.identityId === IdentityId("gm", "facebook")
      }
    }
    "not find deleted sessions" in {
      running(new ShoeboxApplication()) {
        val plugin =
          new SecureSocialAuthenticatorPluginImpl(db, socialUserInfoRepo, userSessionRepo, airbrake, current)
        val id = ExternalId[UserSession]()
        db.readWrite { implicit s =>
          userSessionRepo.save(UserSession(
            externalId = id, socialId = SocialId("gm"), provider = SocialNetworks.FACEBOOK,
            expires = new DateTime("2020-10-20")))
        }
        val authenticator = plugin.find(id.id).right.get.get
        authenticator.expired === false
        authenticator.expirationDate.getMillis === new DateTime("2020-10-20").getMillis
        authenticator.identityId === IdentityId("gm", "facebook")
        plugin.delete(id.id)
        plugin.find(id.id) === Right(None)
      }
    }
    "not get expired sessions" in {
      running(new ShoeboxApplication()) {
        inject[FakeClock].push(new DateTime("2015-01-01"))

        val plugin =
          new SecureSocialAuthenticatorPluginImpl(db, socialUserInfoRepo, userSessionRepo, airbrake, current)
        val id = ExternalId[UserSession]()
        db.readWrite { implicit s =>
          userSessionRepo.save(UserSession(
            externalId = id, socialId = SocialId("gm"), provider = SocialNetworks.FACEBOOK,
            expires = new DateTime("2010-10-20")))
        }
        plugin.find(id.id).right.get === None
      }
    }
    "associate with the correct user and save the session when needed" in {
      running(new ShoeboxApplication()) {
        val plugin =
          new SecureSocialAuthenticatorPluginImpl(db, socialUserInfoRepo, userSessionRepo, airbrake, current)
        val id = ExternalId[UserSession]()
        val socialId = SocialId("gm")
        val provider = SocialNetworks.FACEBOOK
        val user = db.readWrite { implicit s =>
          val user = UserFactory.user().withName("Greg", "Methvin").withUsername("test").saved
          socialUserInfoRepo.save(SocialUserInfo(
            userId = user.id, socialId = socialId, fullName = "Greg Methvin", networkType = provider))
          user
        }
        plugin.save(Authenticator(id.id, IdentityId(socialId.id, provider.name),
          new DateTime, new DateTime, new DateTime("2015-10-20")))
        val authenticator = plugin.find(id.id).right.get.get
        authenticator.identityId.userId === socialId.id
        authenticator.identityId.providerId === provider.name
        inject[FakeSlickSessionProvider].doWithoutCreatingSessions {
          // we should have an old session in the cache and we shouldn't care about updating the last used time
          plugin.save(authenticator.copy(lastUsed = authenticator.lastUsed.plusDays(1)))
        }
        db.readOnlyMaster { implicit s =>
          userSessionRepo.get(id).userId.get === user.id.get
        }
      }
    }
  }
}
