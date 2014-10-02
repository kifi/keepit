package com.keepit.commanders.emails.tips

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.template.{ EmailToSend, TipTemplate }
import com.keepit.model.{ KifiInstallationPlatform, KifiInstallationRepo, User }
import play.twirl.api.Html

import scala.concurrent.Future

class InstallExtensionTip @Inject() (
    db: Database,
    kifiInstallationRepo: KifiInstallationRepo,
    private val airbrake: AirbrakeNotifier) extends TipTemplate {

  /* tip to install the extension if the user hasn't and the user joined from a library invite */
  def render(emailToSend: EmailToSend, userId: Id[User]): Future[Option[Html]] = {
    Future.successful {
      if (!hasInstalledExtension(userId) && hasJoinedFromLibrary(userId)) {
        Some(views.html.email.tips.installExtension())
      } else None
    }
  }

  def hasInstalledExtension(userId: Id[User]) = db.readOnlyReplica { implicit session =>
    kifiInstallationRepo.all(userId).exists(_.platform == KifiInstallationPlatform.Extension)
  }

  // TODO(josh) detect if user joined from library
  def hasJoinedFromLibrary(userId: Id[User]) = false
}
