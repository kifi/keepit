package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.mail.template.EmailToSend

import com.keepit.model.{ NotificationCategory, FeatureWaitlistEntry, FeatureWaitlistRepo }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{ PostOffice, LocalPostOffice, ElectronicMail, EmailAddress, SystemEmailAddress }
import com.keepit.common.logging.Logging
import com.keepit.commanders.emails.{ FeatureWaitlistEmailSender, EmailTemplateSender, EmailOptOutCommander }

class FeatureWaitlistCommander @Inject() (
    db: Database,
    waitlistRepo: FeatureWaitlistRepo,
    waitListSender: FeatureWaitlistEmailSender) extends Logging {

  def waitList(email: String, feature: String, userAgent: String, extIdOpt: Option[ExternalId[FeatureWaitlistEntry]] = None): ExternalId[FeatureWaitlistEntry] = {
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
    waitListSender.sendToUser(EmailAddress(email), feature)
    extId
  }

}
