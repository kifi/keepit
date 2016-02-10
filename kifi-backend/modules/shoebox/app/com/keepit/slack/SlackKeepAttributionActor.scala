package com.keepit.slack

import com.google.inject.Inject
import com.keepit.commanders.KeepSourceCommander
import com.keepit.common.akka.{ SafeFuture, FortyTwoActor }
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier

import com.keepit.model.{ Name, SystemValueRepo }
import com.keepit.slack.models.{ SlackTeamMembershipRepo, SlackTeamMembership }
import com.keepit.social.Author.SlackUser
import com.kifi.juggle._

import scala.concurrent.{ Future, ExecutionContext }

object SlackKeepAttributionActor {
  val slackKeepAttributionSeq = Name[SequenceNumber[SlackTeamMembership]]("slack_keep_attribution")
  val fetchSize: Int = 10
}

class SlackKeepAttributionActor @Inject() (
    db: Database,
    keepSourceCommander: KeepSourceCommander,
    slackTeamMembershipRepo: SlackTeamMembershipRepo,
    systemValueRepo: SystemValueRepo,
    airbrake: AirbrakeNotifier,
    implicit val executionContext: ExecutionContext) extends FortyTwoActor(airbrake) with BatchProcessingActor[SlackTeamMembership] {

  import SlackKeepAttributionActor._

  protected def nextBatch: Future[Seq[SlackTeamMembership]] = {
    log.info(s"Processing attribution for recently updated SlackTeamMemberships...")
    SafeFuture {
      db.readOnlyMaster { implicit session =>
        val seqNum = systemValueRepo.getSequenceNumber(slackKeepAttributionSeq) getOrElse SequenceNumber.ZERO
        slackTeamMembershipRepo.getBySequenceNumber(seqNum, fetchSize)
      }
    }
  }

  protected def processBatch(memberships: Seq[SlackTeamMembership]): Future[Unit] = SafeFuture {
    if (memberships.nonEmpty) {
      memberships.foreach { membership =>
        membership.userId.foreach { userId =>
          keepSourceCommander.reattributeKeeps(SlackUser(membership.slackUserId), userId)
        }
      }
      val maxSeq = memberships.map(_.seq).max
      db.readWrite { implicit session => systemValueRepo.setSequenceNumber(slackKeepAttributionSeq, maxSeq) }
    }
    log.info(s"Processed attribution for ${memberships.length} recently updated SlackTeamMemberships")
  }
}
