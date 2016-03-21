package com.keepit.slack

import com.google.inject.Inject
import com.keepit.commanders.PathCommander
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.time._
import com.keepit.common.util.RightBias.{ RightSide, LeftSide }
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.slack.models._
import com.kifi.juggle._
import org.joda.time.Duration

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object SlackPersonalDigestConfig {
  val minDelayInsideTeam = Duration.standardMinutes(1)

  val maxDelayFromMessageToInitialDigest = Duration.standardMinutes(10)
  val delayAfterSuccessfulDigest = Duration.standardDays(7)
  val delayAfterFailedDigest = Duration.standardDays(1)
  val delayAfterNoDigest = Duration.standardHours(6)
  val maxProcessingDuration = Duration.standardHours(1)
  val minIngestedMessagesForPersonalDigest = 2

  val minDigestConcurrency = 1
  val maxDigestConcurrency = 10

  val superAwesomeWelcomeMessageGIF = "https://d1dwdv9wd966qu.cloudfront.net/img/slack_initial_personal_digest_6.gif"
}

class SlackPersonalDigestNotificationActor @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackMembershipRepo: SlackTeamMembershipRepo,
  digestGenerator: SlackPersonalDigestNotificationGenerator,
  pathCommander: PathCommander,
  slackClient: SlackClientWrapper,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  orgExperimentRepo: OrganizationExperimentRepo,
  slackAnalytics: SlackAnalytics,
  val heimdalContextBuilder: HeimdalContextBuilderFactory,
  implicit val ec: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends FortyTwoActor(airbrake) with ConcurrentTaskProcessingActor[Id[SlackTeamMembership]] {

  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SLACK)
  import SlackPersonalDigestConfig._

  protected val minConcurrentTasks = minDigestConcurrency
  protected val maxConcurrentTasks = maxDigestConcurrency

  protected def pullTasks(limit: Int): Future[Seq[Id[SlackTeamMembership]]] = {
    val now = clock.now
    db.readWriteAsync { implicit session =>
      val ripeIds = slackMembershipRepo.getRipeForPersonalDigest(
        limit = limit,
        overrideProcessesOlderThan = now minus maxProcessingDuration,
        now = now
      )
      ripeIds.filter(id => slackMembershipRepo.markAsProcessingPersonalDigest(id, overrideProcessesOlderThan = now minus maxProcessingDuration))
    }
  }

  protected def processTasks(ids: Seq[Id[SlackTeamMembership]]): Map[Id[SlackTeamMembership], Future[Unit]] = {
    ids.map(id => id -> pushDigestNotificationForUser(id)).toMap
  }

  private def pushDigestNotificationForUser(membershipId: Id[SlackTeamMembership]): Future[Unit] = {
    val now = clock.now
    val (membership, digestOpt) = db.readOnlyMaster { implicit s =>
      val membership = slackMembershipRepo.get(membershipId)
      val digestOpt = digestGenerator.createPersonalDigest(membership)
      (membership, digestOpt)
    }
    digestOpt match {
      case LeftSide(reasonForNoDigest) =>
        db.readWrite { implicit s =>
          slackMembershipRepo.finishProcessing(membershipId, delayAfterNoDigest)
        }
        Future.successful(())
      case RightSide(digest) =>
        val message = if (membership.lastPersonalDigestAt.isEmpty && membership.userId.isEmpty) digestGenerator.messageForFirstTimeDigest(digest) else digestGenerator.messageForRegularDigest(digest)
        slackClient.sendToSlackHoweverPossible(membership.slackTeamId, membership.slackUserId.asChannel, message).map(_ => ()).andThen {
          case Success(_) =>
            db.readWrite { implicit s =>
              slackMembershipRepo.updateLastPersonalDigest(membershipId)
              slackTeamRepo.getBySlackTeamId(membership.slackTeamId).foreach { team =>
                slackTeamRepo.save(team.withNoPersonalDigestsUntil(now plus minDelayInsideTeam))
              }
              slackMembershipRepo.finishProcessing(membershipId, delayAfterSuccessfulDigest)
            }
            val contextBuilder = heimdalContextBuilder()
            contextBuilder += ("numChannelMembers", 1)
            contextBuilder += ("slackTeamName", membership.slackTeamName.value)
            slackAnalytics.trackNotificationSent(membership.slackTeamId, membership.slackUserId.asChannel, membership.slackUsername.asChannelName, NotificationCategory.NonUser.PERSONAL_DIGEST, contextBuilder.build)
            slackLog.info("Personal digest to", membership.slackUsername.value, "in team", membership.slackTeamId.value)
          case Failure(fail) =>
            slackLog.warn(s"Failed to push personal digest to ${membership.slackUsername} in ${membership.slackTeamId} because", fail.getMessage)
            db.readWrite { implicit s =>
              slackMembershipRepo.finishProcessing(membershipId, delayAfterFailedDigest)
            }
        }
    }
  }
}
