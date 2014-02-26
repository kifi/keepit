package com.keepit.eliza.commanders

import com.google.inject.Inject
import com.keepit.eliza.model.{UriRenormalizationTrackingRepo, UserThreadStats, UserThreadRepo}
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.model.User

class ElizaStatsCommander @Inject() (
  userThreadRepo: UserThreadRepo,
  renormalizationRepo: UriRenormalizationTrackingRepo,
  db: Database) extends Logging {

  def getUserThreadStats(userId: Id[User]): UserThreadStats = {
    db.readOnly { implicit s =>
      userThreadRepo.getUserStats(userId)
    }
  }

  def getCurrentRenormalizationSequenceNumber(): SequenceNumber = db.readOnly { implicit session => renormalizationRepo.getCurrentSequenceNumber() }

  }
