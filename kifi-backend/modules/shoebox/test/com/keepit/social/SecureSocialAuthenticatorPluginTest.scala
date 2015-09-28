package com.keepit.social

import com.google.inject.Injector
import com.keepit.common.mail.{ FakeMailModule, EmailAddress }
import com.keepit.common.store.FakeShoeboxStoreModule
import org.joda.time.DateTime
import org.specs2.mutable._

import com.keepit.common.db.{ Id, FakeSlickSessionProvider, ExternalId }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ User, SocialUserInfo, UserSession, UserFactory }
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }

import play.api.Play.current
import play.api.test.Helpers._
import securesocial.core.{ Authenticator, IdentityId }
import com.keepit.common.time.FakeClock
import com.keepit.model.UserFactoryHelper._

class SecureSocialAuthenticatorPluginTest extends Specification with ShoeboxApplicationInjector {

  val modules = Seq(FakeShoeboxStoreModule(), FakeMailModule())

  def airbrake = inject[AirbrakeNotifier]

  private def createPlugin()(implicit injector: Injector) = new SecureSocialAuthenticatorPluginImpl(db, userSessionRepo, inject[UserIdentityHelper], airbrake, current)

  private def runWithIdentity[T](identifier: Either[EmailAddress, SocialId])(test: (SocialNetworkType, SocialId, Id[User]) => T)(implicit injector: Injector): T = {
    val (networkType, socialId, userId) = db.readWrite { implicit s =>
      val userId = UserFactory.user().withName("Greg", "Methvin").withUsername(ExternalId().id).saved.id.get
      val (networkType, socialId) = identifier match {
        case Left(emailAddress) => {
          userEmailAddressCommander.intern(userId, emailAddress).get
          (SocialNetworks.EMAIL, SocialId(emailAddress.address))
        }
        case Right(socialId) => {
          val networkType = SocialNetworks.FACEBOOK
          socialUserInfoRepo.save(SocialUserInfo(userId = Some(userId), socialId = socialId, fullName = "Greg Methvin", networkType = networkType))
          (networkType, socialId)
        }
      }

      (networkType, socialId, userId)
    }
    test(networkType, socialId, userId)
  }

  private def createNewSession(plugin: SecureSocialAuthenticatorPlugin, networkType: SocialNetworkType, socialId: SocialId, expires: DateTime)(implicit injector: Injector): ExternalId[UserSession] = {
    val id = ExternalId[UserSession]()
    plugin.find(id.id) === Right(None)
    db.readWrite { implicit s =>
      userSessionRepo.save(
        UserSession(
          externalId = id,
          socialId = socialId,
          provider = networkType,
          expires = expires
        )
      )
      id
    }
  }

  "SecureSocialAuthenticatorPlugin" should {
    "find existing user sessions" in {
      running(new ShoeboxApplication(modules: _*)) {
        val plugin = createPlugin()
        Seq(Left(EmailAddress("greg@kifi.com")), Right(SocialId("gm"))).map { identifier =>
          runWithIdentity(identifier) {
            case (networkType, socialId, _) =>
              val expires = new DateTime("2020-10-20")
              val id = createNewSession(plugin, networkType, socialId, expires)
              val authenticator = plugin.find(id.id).right.get.get
              authenticator.expired === false
              authenticator.expirationDate.getMillis === expires.getMillis
              authenticator.identityId === IdentityId(socialId.id, networkType.name)
          }
        } last
      }
    }

    "not find deleted sessions" in {
      running(new ShoeboxApplication(modules: _*)) {
        val plugin = createPlugin()
        Seq(Left(EmailAddress("greg@kifi.com")), Right(SocialId("gm"))).map { identifier =>
          runWithIdentity(identifier) {
            case (networkType, socialId, _) =>
              val expires = new DateTime("2020-10-20")
              val id = createNewSession(plugin, networkType, socialId, expires)
              val authenticator = plugin.find(id.id).right.get.get
              authenticator.expired === false
              authenticator.expirationDate.getMillis === expires.getMillis
              authenticator.identityId === IdentityId(socialId.id, networkType.name)
              plugin.delete(id.id)
              plugin.find(id.id) === Right(None)
          }
        } last
      }
    }

    "not get expired sessions" in {
      running(new ShoeboxApplication(modules: _*)) {
        val plugin = createPlugin()
        Seq(Left(EmailAddress("greg@kifi.com")), Right(SocialId("gm"))).map { identifier =>
          runWithIdentity(identifier) {
            case (networkType, socialId, _) =>
              val expires = new DateTime("2010-10-20")
              val id = createNewSession(plugin, networkType, socialId, expires)
              inject[FakeClock].push(new DateTime("2015-01-01"))
              plugin.find(id.id).right.get === None
          }
        } last
      }
    }
    "associate with the correct user and save the session when needed" in {
      running(new ShoeboxApplication(modules: _*)) {
        val plugin = createPlugin()
        Seq(Left(EmailAddress("greg@kifi.com")), Right(SocialId("gm"))).map { identifier =>
          runWithIdentity(identifier) {
            case (networkType, socialId, userId) =>
              val expires = new DateTime("2020-10-20")
              val id = ExternalId[UserSession]()

              plugin.save(Authenticator(id.id, IdentityId(socialId.id, networkType.name), new DateTime, new DateTime, expires))
              val authenticator = plugin.find(id.id).right.get.get
              authenticator.identityId.userId === socialId.id
              authenticator.identityId.providerId === networkType.name
              inject[FakeSlickSessionProvider].doWithoutCreatingSessions {
                // we should have an old session in the cache and we shouldn't care about updating the last used time
                plugin.save(authenticator.copy(lastUsed = authenticator.lastUsed.plusDays(1)))
              }
              db.readOnlyMaster { implicit s =>
                userSessionRepo.get(id).userId.get === userId
              }
          }
        } last
      }
    }
  }
}
