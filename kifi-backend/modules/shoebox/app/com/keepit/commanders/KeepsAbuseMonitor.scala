package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeError, AirbrakeNotifier }
import com.keepit.common.logging.Logging
import com.keepit.model._

class AbuseMonitorException(message: String) extends Exception(message)

class KeepsAbuseMonitor(
    absoluteWarn: Int,
    absoluteError: Int,
    keepRepo: KeepRepo,
    db: Database,
    experiments: UserExperimentRepo,
    airbrake: AirbrakeNotifier) extends Logging {

  if (absoluteWarn >= absoluteError) throw new IllegalStateException(s"absolute warn $absoluteWarn is larger then error $absoluteError")

  def inspect(userId: Id[User], newKeepCount: Int): Unit = {
    val existingBookmarksCount = db.readOnlyReplica { implicit s => keepRepo.getCountByUser(userId) }
    val afterAdding = newKeepCount + existingBookmarksCount
    if (afterAdding > absoluteError) {
      val message = s"user $userId tried to add $newKeepCount keeps while having $existingBookmarksCount. max allowed is $absoluteError"
      if (db.readOnlyMaster { implicit s => experiments.get(userId, ExperimentType.BYPASS_ABUSE_CHECKS).exists(_.isActive) }) {
        log.info(message)
      } else {
        throw new AbuseMonitorException(message)
      }
    }
    if (afterAdding > absoluteWarn) {
      val message = s"user $userId tried to add $newKeepCount keeps while having $existingBookmarksCount. max allowed is $absoluteError"
      if (db.readOnlyMaster { implicit s => experiments.get(userId, ExperimentType.BYPASS_ABUSE_CHECKS).exists(_.isActive) }) {
        log.info(message)
      } else {
        airbrake.notify(new AirbrakeError(message = Some(message), userId = Some(userId)))
      }
    }
  }

}
