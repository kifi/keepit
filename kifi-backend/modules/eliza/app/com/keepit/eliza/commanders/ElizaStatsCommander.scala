package com.keepit.eliza.commanders

import com.google.inject.Inject
import com.keepit.common.performance.StatsdTiming
import com.keepit.eliza.model._
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.model.{ ChangedURI, User }

class ElizaStatsCommander @Inject() (
    userThreadRepo: UserThreadRepo,
    renormalizationRepo: UriRenormalizationTrackingRepo,
    messageRepo: MessageRepo,
    db: Database) extends Logging {

  def getUserThreadStats(userId: Id[User]): UserThreadStats = {
    db.readOnlyReplica { implicit s =>
      userThreadRepo.getUserStats(userId)
    }
  }

  def getCurrentRenormalizationSequenceNumber(): SequenceNumber[ChangedURI] = db.readOnlyReplica { implicit session => renormalizationRepo.getCurrentSequenceNumber() }

  def getSharedThreadsForGroupByWeek(users: Seq[Id[User]]): Seq[GroupThreadStats] = {
    db.readOnlyReplica { implicit s =>
      userThreadRepo.getSharedThreadsForGroupByWeek(users)
    }
  }

  def getAllThreadsForGroupByWeek(users: Seq[Id[User]]): Seq[GroupThreadStats] = {
    db.readOnlyReplica { implicit s =>
      userThreadRepo.getAllThreadsForGroupByWeek(users)
    }
  }

  @StatsdTiming("ElizaStatsCommander.getTotalMessageCountForGroup")
  def getTotalMessageCountForGroup(users: Set[Id[User]]): Int = {
    db.readOnlyReplica { implicit session =>
      val groupThreadStats = userThreadRepo.getSharedThreadsForGroupByWeek(users.toSeq)
      val countByThread = messageRepo.getAllMessageCounts(groupThreadStats.map(stats => Id[MessageThread](stats.threadId)).toSet)
      countByThread.values.sum
    }
  }

}
