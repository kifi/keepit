package com.keepit.common.auth

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ UserIdentity, SocialNetworkType, SocialId, SecureSocialUserPlugin }
import securesocial.core.{ SocialUser, IdentityId, UserService }
import scala.concurrent.duration._

@Singleton
class RemoteLegacyUserService @Inject() (
    airbrake: AirbrakeNotifier,
    shoeboxClient: ShoeboxServiceClient,
    monitoredAwait: MonitoredAwait) extends LegacyUserService with Logging {

  private def reportExceptions[T](f: => T): T =
    try f catch {
      case ex: Throwable =>
        airbrake.notify(ex)
        throw ex
    }

  def find(id: IdentityId): Option[SocialUser] = reportExceptions {
    val resFuture = shoeboxClient.getSocialUserInfoByNetworkAndSocialId(SocialId(id.userId), SocialNetworkType(id.providerId))
    monitoredAwait.result(resFuture, 3 seconds, s"get user for social user ${id.userId} on $id.providerId") match {
      case None =>
        log.info("No SocialUserInfo found for %s".format(id))
        None
      case Some(user) =>
        log.info("User found: %s for %s".format(user, id))
        user.credentials
    }
  }

}
