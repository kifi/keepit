package com.keepit.common.auth

import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.{ LocalUserExperimentCommander, UserCommander }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time.Clock
import com.keepit.model.{ UserEmailAddressRepo, UserCredRepo, UserRepo, SocialUserInfoRepo }
import com.keepit.social._
import com.keepit.common.core._
import securesocial.core.{ Identity, IdentityId }

@Singleton
class ShoeboxLegacyUserService @Inject() (
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  userRepo: UserRepo,
  userCredRepo: UserCredRepo,
  imageStore: S3ImageStore,
  airbrake: AirbrakeNotifier,
  emailRepo: UserEmailAddressRepo,
  socialGraphPlugin: SocialGraphPlugin,
  userCommander: UserCommander,
  userExperimentCommander: LocalUserExperimentCommander,
  clock: Clock)

    extends LegacyUserService with Logging {

  private def reportExceptions[T](f: => T): T = try f catch {
    case ex: Throwable =>
      airbrake.notify(ex)
      throw ex
  }

  def find(id: IdentityId): Option[Identity] = reportExceptions {
    db.readOnlyMaster { implicit s =>
      socialUserInfoRepo.getOpt(SocialId(id.userId), SocialNetworkType(id.providerId))
    } match {
      case None if id.providerId == SocialNetworks.FORTYTWO.authProvider =>
        // Email social accounts are only tied to one email address
        // Since we support multiple email addresses, if we do not
        // find a SUI with the correct email address, we go searching.
        val email = EmailAddress(id.userId)
        db.readOnlyMaster { implicit session =>
          emailRepo.getByAddressOpt(email).flatMap { emailAddr =>
            // todo(andrew): Don't let unverified people log in. For now, we are, but come up with something better.
            socialUserInfoRepo.getByUser(emailAddr.userId).find(_.networkType == SocialNetworks.FORTYTWO).flatMap { sui =>
              sui.credentials map { creds =>
                UserIdentity(Some(emailAddr.userId), creds)
              }
            }
          }
        } tap { res =>
          log.info(s"No immediate SocialUserInfo found for $id, found $res")
        }
      case None =>
        log.info(s"No SocialUserInfo found for $id")
        None
      case Some(user) =>
        log.info(s"User found: $user for $id")
        user.credentials
    }
  }

}
