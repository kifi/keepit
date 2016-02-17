package com.keepit.slack

import com.keepit.commanders._
import com.keepit.common.akka.{ SafeFuture, FortyTwoActor }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.Clock
import com.keepit.common.util.{ LinkElement, DescriptionElements }
import com.keepit.model.LibrarySpace.OrganizationSpace
import com.keepit.slack.models.SlackIntegration.{ ForbiddenSlackIntegration, BrokenSlackIntegration }
import com.keepit.social.BasicUser
import com.kifi.juggle.ConcurrentTaskProcessingActor
import com.keepit.model._
import com.keepit.slack.models._
import org.joda.time.Period
import com.keepit.common.core._
import com.keepit.common.strings._
import com.keepit.common.time._

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success }

object SlackPushingActor {
  val pushTimeout = Period.minutes(10) // we will wait this long for a process to complete before we assume it is incompetent
  val minPushConcurrency = 5
  val maxPushConcurrency = 15

  val delayFromPush = Period.minutes(30)
  val delayFromPushFailure = Period.minutes(5)
  val maxTitleDelayFromKept = Period.seconds(40)
  val maxDelayFromKeptAt = Period.minutes(5)
  val delayFromUpdatedAt = Period.seconds(15)
  val MAX_KEEPS_TO_SEND = 7
  val INTEGRATIONS_BATCH_SIZE = 100
  val KEEP_URL_MAX_DISPLAY_LENGTH = 60
  private val KifiSlackTeamId = SlackTeamId("T02A81H50")
  def canSmartRoute(slackTeamId: SlackTeamId) = slackTeamId == KifiSlackTeamId

  sealed abstract class KeepsToPush
  case class NoKeeps(lastKtlIdOpt: Option[Id[KeepToLibrary]]) extends KeepsToPush
  case class OneKeep(keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution], lastKtlId: Id[KeepToLibrary]) extends KeepsToPush
  case class SomeKeeps(keeps: Seq[Keep], lib: Library, slackTeamId: SlackTeamId, attribution: Map[Id[Keep], SourceAttribution], lastKtlId: Id[KeepToLibrary]) extends KeepsToPush
  case class ManyKeeps(keeps: Seq[Keep], lib: Library, slackTeamId: SlackTeamId, attribution: Map[Id[Keep], SourceAttribution], lastKtlId: Id[KeepToLibrary]) extends KeepsToPush
}

class SlackPushingActor(
  db: Database,
  organizationInfoCommander: OrganizationInfoCommander,
  slackTeamRepo: SlackTeamRepo,
  libRepo: LibraryRepo,
  slackClient: SlackClientWrapper,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  integrationRepo: LibraryToSlackChannelRepo,
  permissionCommander: PermissionCommander,
  clock: Clock,
  ktlRepo: KeepToLibraryRepo,
  keepRepo: KeepRepo,
  keepSourceAttributionRepo: KeepSourceAttributionRepo,
  basicUserRepo: BasicUserRepo,
  pathCommander: PathCommander,
  keepDecorator: KeepDecorator,
  airbrake: AirbrakeNotifier,
  orgConfigRepo: OrganizationConfigurationRepo,
  implicit val executionContext: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends FortyTwoActor(airbrake) with ConcurrentTaskProcessingActor[Id[LibraryToSlackChannel]] {

  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SLACK)

  import SlackPushingActor._

  protected val minConcurrentTasks = minPushConcurrency
  protected val maxConcurrentTasks = maxPushConcurrency

  protected def pullTasks(limit: Int): Future[Seq[Id[LibraryToSlackChannel]]] = {
    db.readWrite { implicit session =>
      val integrationIds = integrationRepo.getRipeForPushing(limit, pushTimeout)
      Future.successful(integrationIds.filter(integrationRepo.markAsPushing(_, pushTimeout)))
    }
  }

  protected def processTasks(integrationIds: Seq[Id[LibraryToSlackChannel]]): Map[Id[LibraryToSlackChannel], Future[Unit]] = {
    val (integrationsByIds, isAllowed, getSettings) = db.readOnlyMaster { implicit session =>
      val integrationsByIds = integrationRepo.getByIds(integrationIds.toSet)

      val isAllowed = integrationsByIds.map {
        case (integrationId, integration) =>
          integrationId -> slackTeamMembershipRepo.getBySlackTeamAndUser(integration.slackTeamId, integration.slackUserId).exists { stm =>
            permissionCommander.getLibraryPermissions(integration.libraryId, stm.userId).contains(LibraryPermission.VIEW_LIBRARY)
          }
      }

      val getSettings = {
        val orgIdsByIntegrationIds = integrationsByIds.mapValues(_.space).collect { case (integrationId, OrganizationSpace(orgId)) => integrationId -> orgId }
        val settingsByOrgIds = orgConfigRepo.getByOrgIds(orgIdsByIntegrationIds.values.toSet).mapValues(_.settings)
        orgIdsByIntegrationIds.mapValues(settingsByOrgIds.apply).get _
      }

      (integrationsByIds, isAllowed, getSettings)
    }
    integrationsByIds.map {
      case (integrationId, integration) =>
        integrationId -> pushMaybe(integration, isAllowed, getSettings).imap(_ => ())
    }
  }

  private def pushMaybe(integration: LibraryToSlackChannel, isAllowed: Id[LibraryToSlackChannel] => Boolean, getSettings: Id[LibraryToSlackChannel] => Option[OrganizationSettings]): Future[Option[Id[KeepToLibrary]]] = {
    val futurePushMaybe = {
      if (isAllowed(integration.id.get)) doPush(integration, getSettings(integration.id.get))
      else Future.failed(ForbiddenSlackIntegration(integration))
    }

    futurePushMaybe andThen {
      case result =>
        val now = clock.now()
        val (nextPushAt, updatedStatus) = result match {
          case Success(_) =>
            val delay = delayFromPush
            (Some(now plus delay), None)
          case Failure(forbidden: ForbiddenSlackIntegration) =>
            slackLog.warn("Push Integration between", forbidden.integration.libraryId, "and", forbidden.integration.slackChannelName.value, "in team", forbidden.integration.slackTeamId.value, "is forbidden")
            (None, Some(SlackIntegrationStatus.Off))
          case Failure(broken: BrokenSlackIntegration) =>
            slackLog.warn("Push Integration between", broken.integration.libraryId, "and", broken.integration.slackChannelName.value, "in team", broken.integration.slackTeamId.value, "is broken")
            SafeFuture {
              val team = db.readOnlyReplica { implicit s =>
                slackTeamRepo.getBySlackTeamId(broken.integration.slackTeamId)
              }
              val org = db.readOnlyMaster { implicit s =>
                team.flatMap(_.organizationId).flatMap(organizationInfoCommander.getBasicOrganizationHelper)
              }
              val name = team.map(_.slackTeamName.value).getOrElse("???")
              val cause = broken.cause.map(_.toString).getOrElse("???")
              inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
                "Can't Push - Broken Slack integration of team", name, "and Kifi org", org, "channel", broken.integration.slackChannelName.value, "cause", cause)))
            }
            (None, Some(SlackIntegrationStatus.Broken))
          case Failure(error) =>
            log.error(s"[SLACK-PUSH] Failed to push to Slack via integration ${integration.id.get}:" + error.getMessage)
            (Some(now plus delayFromPushFailure), None)
        }

        db.readWrite { implicit session =>
          integrationRepo.updateAfterPush(integration.id.get, nextPushAt, updatedStatus getOrElse integration.status)
        }
    }
  }

  private def doPush(integration: LibraryToSlackChannel, settings: Option[OrganizationSettings]): Future[Option[Id[KeepToLibrary]]] = {
    val toBePushed = db.readOnlyReplica { implicit s =>
      val keepsToPush = getKeepsToPushForIntegration(integration)
      describeKeeps(keepsToPush)
    }
    FutureHelpers.foldLeft(toBePushed)(integration.lastProcessedKeep) {
      case (lastProcessedKeep, (messageOpt, newLastProcessedKeep)) =>
        val futureSentToSlack = messageOpt match {
          case Some(message) => slackClient.sendToSlack(integration.slackUserId, integration.slackTeamId, integration.channel, message.quiet) recoverWith {
            case failure @ SlackAPIFailure.NoValidWebhooks => Future.failed(BrokenSlackIntegration(integration, None, Some(failure)))
          }
          case None => Future.successful(())
        }

        futureSentToSlack map { _ =>
          db.readWrite { implicit session =>
            integrationRepo.updateLastProcessedKeep(integration.id.get, newLastProcessedKeep)
          }
          Some(newLastProcessedKeep)
        }
    }
  }

  private def getKeepsToPushForIntegration(lts: LibraryToSlackChannel)(implicit session: RSession): KeepsToPush = {
    val (keeps, lib, lastKtlIdOpt) = {
      val lib = libRepo.get(lts.libraryId)
      val recentKtls = ktlRepo.getByLibraryAddedAfter(lts.libraryId, lts.lastProcessedKeep)
      val keepsByIds = keepRepo.getByIds(recentKtls.map(_.keepId).toSet)
      recentKtls.flatMap(ktl => keepsByIds.get(ktl.keepId).map(_ -> ktl)) unzip match {
        case (recentKeeps, recentKtls) => (recentKeeps, lib, recentKtls.lastOption.map(_.id.get))
      }
    }
    val attributionByKeepId = keepSourceAttributionRepo.getByKeepIds(keeps.flatMap(_.id).toSet)
    def comesFromDestinationChannel(keepId: Id[Keep]): Boolean = attributionByKeepId.get(keepId).exists {
      case sa: SlackAttribution => lts.slackChannelId.contains(sa.message.channel.id)
      case _ => false
    }
    val relevantKeeps = keeps.filter(keep => !comesFromDestinationChannel(keep.id.get))
    relevantKeeps match {
      case Seq() => NoKeeps(lastKtlIdOpt)
      case Seq(keep) => OneKeep(keep, lib, lts.slackTeamId, attributionByKeepId.get(keep.id.get), lastKtlIdOpt.get)
      case keeps if keeps.length <= MAX_KEEPS_TO_SEND => SomeKeeps(keeps, lib, lts.slackTeamId, attributionByKeepId, lastKtlIdOpt.get)
      case keeps => ManyKeeps(keeps, lib, lts.slackTeamId, attributionByKeepId, lastKtlIdOpt.get)
    }
  }

  private def describeKeeps(keeps: KeepsToPush)(implicit session: RSession): Seq[(Option[SlackMessageRequest], Id[KeepToLibrary])] = {
    import DescriptionElements._
    keeps match {
      case NoKeeps(lastKtlIdOpt) => lastKtlIdOpt.toSeq.map((None, _))
      case OneKeep(keep, lib, slackTeamId, attribution, lastKtlId) =>
        val user = keep.userId.flatMap(basicUserRepo.loadActive)
        val msg = keepAsDescriptionElements(keep, lib, slackTeamId, attribution, user)
        val request = SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(msg))
        Seq((Some(request), lastKtlId))
      case SomeKeeps(keeps, lib, slackTeamId, attribution, lastKtlId) =>
        val basicUsersById = basicUserRepo.loadAllActive(keeps.flatMap(_.userId).toSet)
        val msgs = keeps.map(keep => keepAsDescriptionElements(keep, lib, slackTeamId, attribution.get(keep.id.get), keep.userId.flatMap(basicUsersById.get)))
        val request = SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(DescriptionElements.unlines(msgs)))
        Seq((Some(request), lastKtlId))
      case ManyKeeps(keeps, lib, slackTeamId, attribution, lastKtlId) =>
        val msg = DescriptionElements(
          keeps.length, "keeps have been added to",
          lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeamId))
        )
        val request = SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(msg))
        Seq((Some(request), lastKtlId))
    }
  }

  private def keepAsDescriptionElements(keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution], user: Option[BasicUser]): DescriptionElements = {
    import DescriptionElements._

    val slackMessageOpt = attribution.collect { case sa: SlackAttribution => sa.message }
    val shouldSmartRoute = canSmartRoute(slackTeamId)

    val keepLink = LinkElement(if (shouldSmartRoute) pathCommander.keepPageOnUrlViaSlack(keep, slackTeamId).absolute else keep.url)
    val addCommentOpt = {
      if (shouldSmartRoute) Some(DescriptionElements("\n", s"${SlackEmoji.speechBalloon.value} Add a comment" --> LinkElement(pathCommander.keepPageOnKifiViaSlack(keep, slackTeamId))))
      else None
    }
    val libLink = LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeamId).absolute)

    slackMessageOpt match {
      case Some(post) =>
        DescriptionElements(
          keep.title.getOrElse(keep.url.abbreviate(KEEP_URL_MAX_DISPLAY_LENGTH)) --> keepLink,
          "from", s"#${post.channel.name.value}" --> LinkElement(post.permalink),
          "was added to", lib.name --> libLink, addCommentOpt
        )
      case None =>
        val userElement: Option[DescriptionElements] = {
          if (lib.organizationId.isEmpty || !shouldSmartRoute) user.map(fromBasicUser)
          else user.map(basicUser => basicUser.firstName --> LinkElement(pathCommander.userPageViaSlack(basicUser, slackTeamId)))
        }
        val keepElement = keep.title.getOrElse(keep.url.abbreviate(KEEP_URL_MAX_DISPLAY_LENGTH)) --> keepLink
        DescriptionElements(
          userElement.map(ue => DescriptionElements(ue, "added", keepElement)) getOrElse DescriptionElements(keepElement, "was added"),
          "to", lib.name --> libLink,
          keep.note.map(note => DescriptionElements(
            "— “",
            // Slack breaks italics over newlines, so we have to split into lines and italicize each independently
            DescriptionElements.unlines(note.lines.toSeq.map { ln => DescriptionElements("_", Hashtags.format(ln), "_") }),
            "”"
          )),
          addCommentOpt
        )
    }
  }
}

