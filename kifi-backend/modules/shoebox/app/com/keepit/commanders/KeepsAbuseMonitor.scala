package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.model.User
import com.google.inject.Inject
import com.keepit.model.KeepRepo
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{AirbrakeError, AirbrakeNotifier}
import com.keepit.common.logging.Logging

class AbuseMonitorException(message: String) extends Exception(message)

class KeepsAbuseMonitor @Inject() (
    absoluteWarn: Int,
    absoluteError: Int,
    keepRepo: KeepRepo,
    db: Database,
    airbrake: AirbrakeNotifier) extends Logging {

  if (absoluteWarn >= absoluteError) throw new IllegalStateException(s"absolute warn $absoluteWarn is larger then error $absoluteError")

  implicit val dbMasterSlave = Database.Slave

  def inspect(userId: Id[User], newKeepCount: Int): Unit = {
    val existingBookmarksCount = db.readOnlyMaster { implicit s => keepRepo.getCountByUser(userId) }
    val afterAdding = newKeepCount + existingBookmarksCount
    if (afterAdding > absoluteError) {
      throw new AbuseMonitorException(s"user $userId tried to add $newKeepCount keeps while having $existingBookmarksCount. max allowed is $absoluteError")
    }
    if (afterAdding > absoluteWarn) {
      airbrake.notify(AirbrakeError(message = Some(s"user $userId tried to add $newKeepCount keeps while having $existingBookmarksCount. warning threshold is $absoluteWarn"), userId = Some(userId)))
    }
  }

}
