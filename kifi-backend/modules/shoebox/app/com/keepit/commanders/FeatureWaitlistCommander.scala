package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.{ EmailAddress, AmazonSimpleMailProvider, ElectronicMail, SystemEmailAddress }

import com.keepit.model.{ FeatureWaitlistEntry, FeatureWaitlistRepo, NotificationCategory }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.commanders.emails.FeatureWaitlistEmailSender

import scala.concurrent.{ ExecutionContext, Future }

class FeatureWaitlistCommander @Inject() (
    db: Database,
    waitlistRepo: FeatureWaitlistRepo,
    waitListSender: FeatureWaitlistEmailSender,
    implicit val executionContext: ExecutionContext,
    protected val airbrake: AirbrakeNotifier,
    amazonSimpleMailProvider: AmazonSimpleMailProvider) extends Logging {

  def waitList(email: String, feature: String, userAgent: String, extIdOpt: Option[ExternalId[FeatureWaitlistEntry]] = None): Future[ExternalId[FeatureWaitlistEntry]] = {
    val existingOpt: Option[FeatureWaitlistEntry] = extIdOpt.flatMap { db.readOnlyReplica { implicit session => waitlistRepo.getOpt(_) } }
    val extId = existingOpt.map { existing =>
      db.readWrite { implicit session => waitlistRepo.save(existing.copy(email = email, feature = feature, userAgent = userAgent)) }.externalId
    } getOrElse {
      db.readWrite { implicit session =>
        waitlistRepo.save(FeatureWaitlistEntry(
          email = email,
          feature = feature,
          userAgent = userAgent
        ))
      }.externalId
    }
    if (feature == "teams") {
      amazonSimpleMailProvider.sendMail(ElectronicMail(
        from = SystemEmailAddress.ENG,
        to = Seq(SystemEmailAddress.SALES),
        subject = s"New entry on 'Teams' waitlist: $email",
        htmlBody = "",
        category = NotificationCategory.toElectronicMailCategory(NotificationCategory.System.LEADS)
      ))
      Future.successful(extId)
    } else {
      waitListSender.sendToUser(EmailAddress(email), feature) recover {
        case e: IllegalArgumentException => airbrake.notify("unrecognized wait-list feature request", e)
      } map (_ => extId)
    }
  }

}
