package com.keepit.slack

import com.google.inject.ImplementedBy
import com.keepit.common.db.Id

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLogging
import com.keepit.common.time._
import com.keepit.common.util.{ DescriptionElements, LinkElement, Ord }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.slack.models._
import org.joda.time.Period

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[SlackDigestNotifierImpl])
trait SlackDigestNotifier {
  def pushDigestNotificationsForRipeTeams(): Future[Unit]
  def pushDigestNotificationsForRipeChannels(): Future[Unit]
}

@Singleton
class SlackDigestNotifierImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  channelToLibRepo: SlackChannelToLibraryRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  slackClient: SlackClientWrapper,
  pathCommander: PathCommander,
  permissionCommander: PermissionCommander,
  orgCommander: OrganizationCommander,
  orgAvatarCommander: OrganizationAvatarCommander,
  libraryCommander: LibraryCommander,
  libRepo: LibraryRepo,
  ktlRepo: KeepToLibraryRepo,
  keepRepo: KeepRepo,
  attributionRepo: KeepSourceAttributionRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  orgMembershipCommander: OrganizationMembershipCommander,
  organizationInfoCommander: OrganizationInfoCommander,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  val inhouseSlackClient: InhouseSlackClient)
    extends SlackDigestNotifier with SlackLogging {
  val loggingDestination = InhouseSlackChannel.TEST_RYAN

  def pushDigestNotificationsForRipeChannels(): Future[Unit] = {
    ???
  }

  def pushDigestNotificationsForRipeTeams(): Future[Unit] = {
    val ripeTeamsFut = db.readOnlyReplicaAsync { implicit s =>
      slackTeamRepo.getRipeForPushingDigestNotification(lastPushOlderThan = clock.now minus SlackCommander.minPeriodBetweenDigestNotifications)
    }
    for {
      ripeTeams <- ripeTeamsFut
      pushes <- FutureHelpers.accumulateRobustly(ripeTeams)(pushDigestNotificationForTeam)
    } yield Unit
  }

  private def createSlackDigest(slackTeam: SlackTeam)(implicit session: RSession): Option[SlackTeamDigest] = {
    for {
      org <- slackTeam.organizationId.flatMap(organizationInfoCommander.getBasicOrganizationHelper)
      numIngestedKeepsByLibrary = {
        val teamIntegrations = channelToLibRepo.getBySlackTeam(slackTeam.slackTeamId)
        val teamChannelIds = teamIntegrations.flatMap(_.slackChannelId).toSet
        val librariesIngestedInto = libRepo.getActiveByIds(teamIntegrations.map(_.libraryId).toSet).filter {
          case (_, lib) =>
            Set[LibraryVisibility](LibraryVisibility.ORGANIZATION, LibraryVisibility.PUBLISHED).contains(lib.visibility)
        }
        librariesIngestedInto.map {
          case (libId, lib) =>
            val newKeepIds = ktlRepo.getByLibraryAddedSince(libId, slackTeam.lastDigestNotificationAt).map(_.keepId).toSet
            val newSlackKeeps = keepRepo.getByIds(newKeepIds).values.filter(_.source == KeepSource.slack).map(_.id.get).toSet
            val numIngestedKeeps = attributionRepo.getByKeepIds(newSlackKeeps).values.collect {
              case SlackAttribution(msg) if teamChannelIds.contains(msg.channel.id) => 1
            }.sum
            lib -> numIngestedKeeps
        }
      }
      digest <- Some(SlackTeamDigest(
        slackTeam = slackTeam,
        timeSinceLastDigest = new Period(slackTeam.lastDigestNotificationAt, clock.now),
        org = org,
        numIngestedKeepsByLibrary = numIngestedKeepsByLibrary
      )).filter(_.numIngestedKeeps >= 10)
    } yield digest
  }

  private def describeDigest(digest: SlackTeamDigest)(implicit session: RSession): SlackMessageRequest = {
    import DescriptionElements._
    val topLibraries = digest.numIngestedKeepsByLibrary.toList.sortBy { case (lib, numKeeps) => numKeeps }(Ord.descending).take(3).collect { case (lib, numKeeps) if numKeeps > 0 => lib }
    val lines = List(
      DescriptionElements("We have captured", digest.numIngestedKeeps, "links from", digest.slackTeam.slackTeamName.value, "in the last", digest.timeSinceLastDigest.getDays, "days"),
      DescriptionElements("Your most active", if (topLibraries.length > 1) "libraries are" else "library is",
        DescriptionElements.unwordsPretty(topLibraries.map(lib => DescriptionElements(lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute))))
      ),
      DescriptionElements(
        "Check them at at", digest.org, "'s page on Kifi,",
        "search through them using the /kifi Slack command,",
        "and see them in your Google searches when you install the", "browser extension" --> LinkElement(PathCommander.browserExtension)
      )
    )

    SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(DescriptionElements.unlines(lines)))
  }
  private def pushDigestNotificationForTeam(team: SlackTeam): Future[Unit] = {
    val now = clock.now
    val msgOpt = db.readOnlyMaster { implicit s => createSlackDigest(team).map(describeDigest) }
    val generalChannelFut = team.generalChannelId match {
      case Some(channelId) => Future.successful(Some(channelId))
      case None => slackClient.getGeneralChannelId(team.slackTeamId)
    }
    generalChannelFut.flatMap { generalChannelOpt =>
      val pushOpt = for {
        msg <- msgOpt
        generalChannel <- generalChannelOpt
      } yield {
        slackClient.sendToSlackTeam(team.slackTeamId, generalChannel, msg).andThen {
          case Success(_: Unit) =>
            db.readWrite { implicit s =>
              slackTeamRepo.save(slackTeamRepo.get(team.id.get).withGeneralChannelId(generalChannel).withLastDigestNotificationAt(now))
            }
            slackLog.info("Pushed a digest to", team.slackTeamName.value)
          case Failure(fail) =>
            slackLog.warn("Failed to push a digest to", team.slackTeamName.value, "because", fail.getMessage)
        }
      }
      pushOpt.getOrElse(Future.successful(Unit))
    }
  }
}
