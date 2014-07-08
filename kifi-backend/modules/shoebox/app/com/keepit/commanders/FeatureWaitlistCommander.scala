package com.keepit.commanders

import com.google.inject.Inject

import com.keepit.model.{NotificationCategory, FeatureWaitlistEntry, FeatureWaitlistRepo}
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{PostOffice, LocalPostOffice, ElectronicMail, EmailAddress, SystemEmailAddress}
import com.keepit.common.logging.Logging
import com.keepit.commanders.emails.EmailOptOutCommander

class FeatureWaitlistCommander @Inject() (db: Database, waitlistRepo: FeatureWaitlistRepo, postOffice: LocalPostOffice, emailOptOutCommander: EmailOptOutCommander) extends Logging {


  val emailTriggers = Map(
     "mobile_app" -> (views.html.email.mobileWaitlistInlined, views.html.email.mobileWaitlistText)
  )

  private def triggerEmail(feature: String, email: String) : Unit = {
    emailTriggers.get(feature).foreach{ template =>
      val unsubLink = s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(EmailAddress(email)))}"
      db.readWrite{ implicit session =>
        postOffice.sendMail(ElectronicMail(
          senderUserId = None,
          from = SystemEmailAddress.NOTIFICATIONS,
          fromName = Some("Kifi"),
          to = List(EmailAddress(email)),
          subject = s"You're on the wait list",
          htmlBody = template._1(unsubLink).body,
          textBody = Some(template._2(unsubLink).body),
          category = NotificationCategory.User.WAITLIST)
        )
      }
    }

  }

  def waitList(email: String, feature: String, userAgent: String, extIdOpt: Option[ExternalId[FeatureWaitlistEntry]] = None) : ExternalId[FeatureWaitlistEntry] = {
    val existingOpt : Option[FeatureWaitlistEntry] = extIdOpt.flatMap{ db.readOnlyMaster{ implicit session => waitlistRepo.getOpt(_) } }
    val extId = existingOpt.map{ existing =>
      db.readWrite{ implicit session => waitlistRepo.save(existing.copy(email=email, feature=feature, userAgent=userAgent)) }.externalId
    } getOrElse {
      db.readWrite{ implicit session =>
        waitlistRepo.save(FeatureWaitlistEntry(
          email=email,
          feature=feature,
          userAgent=userAgent
        ))
      }.externalId
    }
    triggerEmail(feature, email)
    extId
  }


}
