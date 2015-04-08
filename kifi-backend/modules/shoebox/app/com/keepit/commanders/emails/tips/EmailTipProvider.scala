package com.keepit.commanders.emails.tips

import com.google.inject.Inject
import com.keepit.commanders.emails.FriendRecommendationsEmailTip
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.template.{ EmailToSend, EmailTip }
import com.keepit.model.{ User, UserEmailAddressRepo }
import com.keepit.social.SocialNetworks.{ FACEBOOK, LINKEDIN }
import play.twirl.api.Html

import scala.concurrent.{ ExecutionContext, Future }

class EmailTipProvider @Inject() (
    db: Database,
    emailRepo: UserEmailAddressRepo,
    peopleRecommendationsTip: FriendRecommendationsEmailTip,
    connectNetworkTip: ConnectNetworkTip,
    installExtensionTip: InstallExtensionTip,
    importGmail: ImportGmailContacts,
    keepFromEmailTip: KeepFromEmailTip,
    implicit val executionContext: ExecutionContext,
    bookmarkImportTip: BookmarkImportTip) {

  def getTipHtml(emailToSend: EmailToSend) = {
    val userIdOpt = emailToSend.to.fold(userId => Some(userId), { emailAddr =>
      db.readOnlyReplica { implicit session =>
        emailRepo.getByAddress(emailAddr).headOption.map(_.userId)
      }
    })

    // many tips require a User, so use a different transform functions for User and non-User
    val transform: EmailTip => Future[(EmailTip, Option[Html])] =
      userIdOpt map { userId => userTipMatcher(emailToSend, userId) } getOrElse {
        // when we have tips for non-User emails, they will go here
        tip => Future.successful((tip, None))
      }

    // get the first available Tip for this email that returns Some
    FutureHelpers.findMatching[EmailTip, (EmailTip, Option[Html])](emailToSend.tips, 1, userTipPredicate, transform).map { seqOpts =>
      seqOpts.dropWhile(_._2.isEmpty).headOption.flatMap {
        case (emailTip, Some(html)) => Option((emailTip, html))
        case _ => None
      }
    }
  }

  private def userTipMatcher(emailToSend: EmailToSend, userId: Id[User]) = {
    lazy val connectNetwork = connectNetworkTip.render(emailToSend, userId)

    (tip: EmailTip) => {
      val htmlOptF = tip match {
        case EmailTip.FriendRecommendations => peopleRecommendationsTip.render(emailToSend, userId)
        case EmailTip.ConnectFacebook => connectNetwork(FACEBOOK)
        case EmailTip.ConnectLinkedIn => connectNetwork(LINKEDIN)
        case EmailTip.ImportGmailContacts => importGmail.render(emailToSend)
        case EmailTip.InstallExtension => installExtensionTip.render(emailToSend, userId)
        case EmailTip.KeepFromEmail => keepFromEmailTip.render(emailToSend)
        case EmailTip.BookmarkImport => bookmarkImportTip.render(emailToSend)
        case _ => Future.successful(None)
      }
      htmlOptF map ((tip, _))
    }
  }

  private def userTipPredicate(tipHtml: (EmailTip, Option[Html])) = tipHtml._2.isDefined

}
