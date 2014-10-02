package com.keepit.commanders.emails.tips

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.template.{ EmailToSend, TipTemplate }
import com.keepit.model.{ User, SocialUserInfoRepo, UserEmailAddressRepo }
import com.keepit.social.SocialNetworkType
import com.keepit.social.SocialNetworks.{ LINKEDIN, FACEBOOK }

import scala.concurrent.Future

class ConnectNetworkTip @Inject() (
    db: Database,
    emailRepo: UserEmailAddressRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    private val airbrake: AirbrakeNotifier) extends TipTemplate {

  def render(emailToSend: EmailToSend, userId: Id[User]) = {
    val socialUsers = db.readOnlyReplica { implicit session =>
      socialUserInfoRepo.getByUser(userId)
    }

    // returns function so only one query via SocialUserInfoRepo is necessary if the caller
    // wants to check if both FB and LinkedIn are connected
    (network: SocialNetworkType) => {
      val isConnected = socialUsers.exists(su => su.networkType == network && su.getProfileUrl.isDefined)

      Future.successful {
        if (isConnected) None
        else {
          Some(network) collect {
            case FACEBOOK => views.html.email.tips.connectFacebook()
            case LINKEDIN => views.html.email.tips.connectLinkedIn()
          }
        }
      }
    }
  }
}
