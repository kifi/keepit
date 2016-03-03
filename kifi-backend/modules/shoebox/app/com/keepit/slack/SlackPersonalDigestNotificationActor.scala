package com.keepit.slack

import com.google.inject.Inject
import com.keepit.commanders.{ OrganizationInfoCommander, PathCommander }
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core.futureExtensionOps
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.RandomChoice._
import com.keepit.common.util.{ DescriptionElements, LinkElement, Ord }
import com.keepit.model._
import com.keepit.slack.models._
import com.kifi.juggle._
import org.joda.time.{ Duration, Period }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

object SlackPersonalDigestConfig {
  val delayBeforeFirstDigest = Duration.standardMinutes(1)
  val minDelayBetweenPersonalDigests = Duration.standardDays(3)
  val minDelayInsideTeam = Duration.standardMinutes(10)
  val delayAfterSuccessfulDigest = Duration.standardDays(3)
  val delayAfterFailedDigest = Duration.standardDays(1)
  val delayAfterNoDigest = Duration.standardDays(3)
  val maxProcessingDuration = Duration.standardHours(1)
  val minIngestedLinksForPersonalDigest = 10

  val minDigestConcurrency = 0
  val maxDigestConcurrency = 10
}

class SlackPersonalDigestNotificationActor @Inject() (
  db: Database,
  channelToLibRepo: SlackChannelToLibraryRepo,
  slackTeamRepo: SlackTeamRepo,
  slackMembershipRepo: SlackTeamMembershipRepo,
  orgConfigRepo: OrganizationConfigurationRepo,
  libRepo: LibraryRepo,
  attributionRepo: KeepSourceAttributionRepo,
  ktlRepo: KeepToLibraryRepo,
  keepRepo: KeepRepo,
  basicUserRepo: BasicUserRepo,
  pathCommander: PathCommander,
  slackClient: SlackClientWrapper,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  orgInfoCommander: OrganizationInfoCommander,
  orgExperimentRepo: OrganizationExperimentRepo,
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
      val vipTeams = {
        val orgs = orgExperimentRepo.getOrganizationsByExperiment(OrganizationExperimentType.SLACK_PERSONAL_DIGESTS).toSet
        slackTeamRepo.getByOrganizationIds(orgs).values.flatten.map(_.slackTeamId).toSet
      }
      val ripeIds = slackMembershipRepo.getRipeForPersonalDigest(
        limit = limit,
        overrideProcessesOlderThan = now minus maxProcessingDuration,
        upperBoundForTeam = now minus minDelayInsideTeam,
        vipTeams = vipTeams
      )
      ripeIds.filter(id => slackMembershipRepo.markAsProcessing(id, overrideProcessesOlderThan = now minus maxProcessingDuration))
    }
  }

  protected def processTasks(ids: Seq[Id[SlackTeamMembership]]): Map[Id[SlackTeamMembership], Future[Unit]] = {
    ids.map(id => id -> pushDigestNotificationForUser(id)).toMap
  }

  private def pushDigestNotificationForUser(membershipId: Id[SlackTeamMembership]): Future[Unit] = {
    val now = clock.now
    val (membership, digestOpt) = db.readOnlyMaster { implicit s =>
      val membership = slackMembershipRepo.get(membershipId)
      val digestOpt = createPersonalDigest(membership)
      (membership, digestOpt)
    }
    digestOpt match {
      case None =>
        db.readWrite { implicit s =>
          slackMembershipRepo.finishProcessing(membershipId, delayAfterNoDigest)
        }
        Future.successful(())
      case Some(digest) =>
        slackClient.sendToSlackHoweverPossible(membership.slackTeamId, membership.slackUserId.asChannel, describePersonalDigest(digest)).map(_ => ()).andThen {
          case Success(_) =>
            db.readWrite { implicit s =>
              slackMembershipRepo.updateLastPersonalDigest(membershipId)
              slackTeamRepo.getBySlackTeamId(membership.slackTeamId).foreach { team =>
                slackTeamRepo.save(team.withNoPersonalDigestsBefore(now plus minDelayInsideTeam))
              }
              slackMembershipRepo.finishProcessing(membershipId, delayAfterSuccessfulDigest)
            }
            slackLog.info("Sent a personal digest to", membership.slackUsername.value, "in team", membership.slackTeamId.value)
          case Failure(fail) =>
            slackLog.warn(s"Failed to push personal digest to ${membership.slackUsername} in ${membership.slackTeamId} because", fail.getMessage)
            db.readWrite { implicit s =>
              slackMembershipRepo.finishProcessing(membershipId, delayAfterFailedDigest)
            }
        }
    }
  }

  private def createPersonalDigest(membership: SlackTeamMembership)(implicit session: RSession): Option[SlackPersonalDigest] = {
    for {
      slackTeam <- slackTeamRepo.getBySlackTeamId(membership.slackTeamId)
      org <- slackTeam.organizationId.flatMap(orgInfoCommander.getBasicOrganizationHelper)
      librariesByChannel = {
        val teamIntegrations = channelToLibRepo.getBySlackTeam(slackTeam.slackTeamId).filter(_.isWorking)
        val librariesById = libRepo.getActiveByIds(teamIntegrations.map(_.libraryId).toSet)
        teamIntegrations.groupBy(_.slackChannelId).collect {
          case (Some(channelId), integrations) =>
            channelId -> integrations.flatMap(sctl => librariesById.get(sctl.libraryId)).filter { lib =>
              (lib.visibility, lib.organizationId) match {
                case (LibraryVisibility.PUBLISHED, _) => true
                case (LibraryVisibility.ORGANIZATION, Some(orgId)) if slackTeam.organizationId.contains(orgId) => true
                case _ => false
              }
            }.toSet
        }
      }
      ingestedLinksByChannel = librariesByChannel.map {
        case (channelId, libs) =>
          val newKeepIds = ktlRepo.getByLibrariesAddedSince(libs.map(_.id.get), membership.unnotifiedSince).map(_.keepId).toSet
          val newSlackKeepsById = keepRepo.getByIds(newKeepIds).filter { case (_, keep) => keep.source == KeepSource.slack }
          val ingestedLinks = attributionRepo.getByKeepIds(newSlackKeepsById.keySet).collect {
            case (kId, SlackAttribution(msg, teamId)) if teamId == slackTeam.slackTeamId && msg.channel.id == channelId && msg.userId == membership.slackUserId =>
              newSlackKeepsById.get(kId).map(_.url)
          }.flatten.toSet
          channelId -> ingestedLinks
      }
      digest <- Some(SlackPersonalDigest(
        slackTeam = slackTeam,
        digestPeriod = new Duration(membership.unnotifiedSince, clock.now),
        org = org,
        ingestedLinksByChannel = ingestedLinksByChannel,
        librariesByChannel = librariesByChannel
      )).filter(_.numIngestedLinks >= minIngestedLinksForPersonalDigest)
    } yield digest
  }

  // "Pure" function
  private def describePersonalDigest(digest: SlackPersonalDigest): SlackMessageRequest = {
    import DescriptionElements._
    val slackTeamId = digest.slackTeam.slackTeamId
    val topLibraries = digest.numIngestedLinksByLibrary.toList.sortBy { case (lib, numLinks) => numLinks }(Ord.descending).take(3).collect { case (lib, numLinks) if numLinks > 0 => lib }
    val text = DescriptionElements.unlines(List(
      DescriptionElements("We have collected", s"${digest.numIngestedLinks} links" --> LinkElement(pathCommander.orgPageViaSlack(digest.org, slackTeamId)),
        "from", digest.slackTeam.slackTeamName.value, inTheLast(digest.digestPeriod),
        SlackEmoji.gear, "Edit", "settings" --> LinkElement(pathCommander.orgIntegrationsPageViaSlack(digest.org, slackTeamId)), ".")
    ))
    val attachments = List(
      SlackAttachment(color = Some(LibraryColor.GREEN.hex), text = Some(DescriptionElements.formatForSlack(DescriptionElements(
        "Your most active", if (topLibraries.length > 1) "libraries are" else "library is",
        DescriptionElements.unwordsPretty {
          topLibraries.map(lib => DescriptionElements(lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, digest.slackTeam.slackTeamId))))
        }
      )))).withFullMarkdown
    )
    SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(text), attachments).quiet
  }
}
