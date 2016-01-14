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

object SlackDigestNotifier {
  val minPeriodBetweenTeamDigests = Period.weeks(1)
  val minPeriodBetweenChannelDigests = Period.days(4)
  val minIngestedKeepsForChannelDigest = 5
  val minIngestedKeepsForTeamDigest = 10
  val KifiSlackTeamId = SlackTeamId("T02A81H50")
}

@Singleton
class SlackDigestNotifierImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackChannelRepo: SlackChannelRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  channelToLibRepo: SlackChannelToLibraryRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  slackClient: SlackClientWrapper,
  pathCommander: PathCommander,
  libRepo: LibraryRepo,
  ktlRepo: KeepToLibraryRepo,
  keepRepo: KeepRepo,
  attributionRepo: KeepSourceAttributionRepo,
  organizationInfoCommander: OrganizationInfoCommander,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  val inhouseSlackClient: InhouseSlackClient)
    extends SlackDigestNotifier with SlackLogging {
  val loggingDestination = InhouseSlackChannel.TEST_RYAN

  def pushDigestNotificationsForRipeChannels(): Future[Unit] = {
    val ripeChannelsFut = db.readOnlyReplicaAsync { implicit s =>
      // TODO(ryan): right now this only sends digests to channels in the Kifi slack team, once it works change that
      slackChannelRepo.getRipeForPushingDigestNotification(lastPushOlderThan = clock.now minus SlackDigestNotifier.minPeriodBetweenChannelDigests).filter {
        _.slackTeamId == SlackDigestNotifier.KifiSlackTeamId
      }
    }
    for {
      ripeChannels <- ripeChannelsFut
      pushes <- FutureHelpers.accumulateRobustly(ripeChannels)(pushDigestNotificationForChannel)
    } yield Unit
  }

  def pushDigestNotificationsForRipeTeams(): Future[Unit] = {
    val ripeTeamsFut = db.readOnlyReplicaAsync { implicit s =>
      slackTeamRepo.getRipeForPushingDigestNotification(lastPushOlderThan = clock.now minus SlackDigestNotifier.minPeriodBetweenTeamDigests).filter {
        _.slackTeamId == SlackDigestNotifier.KifiSlackTeamId
      }
    }
    for {
      ripeTeams <- ripeTeamsFut
      pushes <- FutureHelpers.accumulateRobustly(ripeTeams)(pushDigestNotificationForTeam)
    } yield Unit
  }

  private def createTeamDigest(slackTeam: SlackTeam)(implicit session: RSession): Option[SlackTeamDigest] = {
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
      )).filter(_.numIngestedKeeps >= SlackDigestNotifier.minIngestedKeepsForTeamDigest)
    } yield digest
  }

  private def describeTeamDigest(digest: SlackTeamDigest)(implicit session: RSession): SlackMessageRequest = {
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
    val msgOpt = db.readOnlyMaster { implicit s => createTeamDigest(team).map(describeTeamDigest) }
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

  private def createChannelDigest(slackChannel: SlackChannel)(implicit session: RSession): Option[SlackChannelDigest] = {
    val ingestions = channelToLibRepo.getBySlackTeamAndChannel(slackChannel.slackTeamId, slackChannel.slackChannelId)
    val librariesIngestedInto = libRepo.getActiveByIds(ingestions.map(_.libraryId).toSet)
    val numIngestedKeeps = librariesIngestedInto.keySet.headOption.map { libId =>
      val newKeepIds = ktlRepo.getByLibraryAddedSince(libId, slackChannel.lastNotificationAt).map(_.keepId).toSet
      val newSlackKeeps = keepRepo.getByIds(newKeepIds).values.filter(_.source == KeepSource.slack).map(_.id.get).toSet
      attributionRepo.getByKeepIds(newSlackKeeps).values.count {
        case SlackAttribution(msg) => msg.channel.id == slackChannel.slackChannelId
        case _ => false
      }
    }.getOrElse(0)

    Some(SlackChannelDigest(
      slackChannel = slackChannel,
      timeSinceLastDigest = new Period(slackChannel.lastNotificationAt, clock.now),
      numIngestedKeeps = numIngestedKeeps,
      libraries = librariesIngestedInto.values.toList
    )).filter(_.numIngestedKeeps >= SlackDigestNotifier.minIngestedKeepsForChannelDigest)
  }

  private def describeChannelDigest(digest: SlackChannelDigest)(implicit session: RSession): SlackMessageRequest = {
    import DescriptionElements._
    val lines = List(
      DescriptionElements("We have captured", digest.numIngestedKeeps, "links from", digest.slackChannel.slackChannelName.value, "in the last", digest.timeSinceLastDigest.getDays, "days"),
      DescriptionElements("You can find them in",
        DescriptionElements.unwordsPretty(digest.libraries.map(lib => DescriptionElements(lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute)))),
        "or search them using the /kifi Slack command.")
    )

    SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(DescriptionElements.unlines(lines)))
  }
  private def pushDigestNotificationForChannel(channel: SlackChannel): Future[Unit] = {
    val now = clock.now
    val msgOpt = db.readOnlyMaster { implicit s => createChannelDigest(channel).map(describeChannelDigest) }
    val pushOpt = for {
      msg <- msgOpt
    } yield {
      slackClient.sendToSlackChannel(channel.slackTeamId, channel.idAndName, msg).andThen {
        case Success(_: Unit) =>
          db.readWrite { implicit s =>
            slackChannelRepo.save(slackChannelRepo.get(channel.id.get).withLastNotificationAt(now))
          }
          slackLog.info("Pushed a digest to", channel.slackChannelName.value, "in team", channel.slackTeamId.value)
        case Failure(fail) =>
          slackLog.warn("Failed to push a digest to", channel.slackChannelName.value, "in team", channel.slackTeamId.value)
      }
    }
    pushOpt.getOrElse(Future.successful(Unit))
  }
}
