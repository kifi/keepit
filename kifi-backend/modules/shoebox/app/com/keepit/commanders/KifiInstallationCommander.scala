package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model._
import org.joda.time.DateTime

class KifiInstallationCommander @Inject() (
    kifiInstallationRepo: KifiInstallationRepo,
    db: Database) extends Logging {

  def lastMobileAppStartTime(userId: Id[User]): DateTime = db.readOnlyReplica { implicit s =>
    val installation = kifiInstallationRepo.lastUpdatedMobile(userId).getOrElse(throw new Exception(s"should not get to this point if user $userId has no mobile installation"))
    installation.updatedAt
  }

  def isMobileVersionEqualOrGreaterThen(userId: Id[User], android: KifiAndroidVersion, ios: KifiIPhoneVersion): Boolean = {
    db.readOnlyReplica { implicit s => kifiInstallationRepo.lastUpdatedMobile(userId) } exists { installation =>
      installation.platform match {
        case KifiInstallationPlatform.Android =>
          installation.version.compareIt(android) >= 0
        case KifiInstallationPlatform.IPhone =>
          installation.version.compareIt(ios) >= 0
        case _ =>
          throw new Exception(s"Don't know platform for $installation")
      }
    }
  }

}
