package com.keepit.slack

import java.util.concurrent.atomic.AtomicBoolean

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core.futureExtensionOps
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance.StatsdTimingAsync
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.strings._
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.social.BasicUser
import com.kifi.juggle.ConcurrentTaskProcessingActor.IfYouCouldJustGoAhead
import org.joda.time.{ DateTime, Duration }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object LibraryToSlackChannelPusher {
  val maxAcceptableProcessingDuration = Duration.standardMinutes(10) // we will wait this long for a process to complete before we assume it is incompetent
  val delayFromFullPush = Duration.standardMinutes(30)
  val delayFromPartialPush = Duration.standardSeconds(15)
  val delayFromPushRequest = Duration.standardSeconds(20)
  val maxTitleDelayFromKept = Duration.standardSeconds(40)
  val maxDelayFromKeptAt = Duration.standardMinutes(5)
  val delayFromUpdatedAt = Duration.standardSeconds(15)
  val MAX_KEEPS_TO_SEND = 7
  val INTEGRATIONS_BATCH_SIZE = 100
  val KEEP_URL_MAX_DISPLAY_LENGTH = 60

  val pushing = new AtomicBoolean()
}

@ImplementedBy(classOf[LibraryToSlackChannelPusherImpl])
trait LibraryToSlackChannelPusher {
  // Method called by scheduled plugin
  def findAndPushUpdatesForRipestIntegrations(): Future[Map[Id[LibraryToSlackChannel], Boolean]]

  // Method to be called if something happens in a library
  def schedule(libIds: Set[Id[Library]]): Unit
}

@Singleton
class LibraryToSlackChannelPusherImpl @Inject() (
  db: Database,
  inhouseSlackClient: InhouseSlackClient,
  organizationInfoCommander: OrganizationInfoCommander,
  orgExperimentRepo: OrganizationExperimentRepo,
  slackTeamRepo: SlackTeamRepo,
  libRepo: LibraryRepo,
  slackClient: SlackClientWrapper,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  permissionCommander: PermissionCommander,
  clock: Clock,
  ktlRepo: KeepToLibraryRepo,
  keepRepo: KeepRepo,
  keepSourceAttributionRepo: KeepSourceAttributionRepo,
  basicUserRepo: BasicUserRepo,
  pathCommander: PathCommander,
  keepDecorator: KeepDecorator,
  eliza: ElizaServiceClient,
  airbrake: AirbrakeNotifier,
  pushingActor: ActorInstance[SlackPushingActor],
  slackAnalytics: SlackAnalytics,
  implicit val executionContext: ExecutionContext)
    extends LibraryToSlackChannelPusher with Logging {

  import LibraryToSlackChannelPusher._

  @StatsdTimingAsync("LibraryToSlackChannelPusher.findAndPushUpdatesForRipestLibraries")
  def findAndPushUpdatesForRipestIntegrations(): Future[Map[Id[LibraryToSlackChannel], Boolean]] = if (pushing.getAndSet(true)) Future.successful(Map.empty) else {
    val futurePushed = FutureHelpers.foldLeftUntil(Stream.continually(()))(Map.empty[Id[LibraryToSlackChannel], Boolean]) {
      case (pushedSoFar, _) =>
        val integrationsToProcess = db.readWrite { implicit s =>
          val newActorTeams = {
            val orgs = orgExperimentRepo.getOrganizationsByExperiment(OrganizationExperimentType.SLACK_COMMENT_MIRRORING).toSet
            slackTeamRepo.getByOrganizationIds(orgs).values.flatten.map(_.slackTeamId).toSet
          }
          val integrations = libToChannelRepo.getRipeForPushing(INTEGRATIONS_BATCH_SIZE, maxAcceptableProcessingDuration, newActorTeams)
          markLegalIntegrationsForProcessing(integrations)
        }
        processIntegrations(integrationsToProcess).map { pushedBatch =>
          (pushedSoFar ++ pushedBatch, pushedBatch.isEmpty)
        }
    }
    futurePushed andThen { case _ => pushing.set(false) }
  }

  def schedule(libIds: Set[Id[Library]]): Unit = {
    val weShouldPushImmediately = db.readWrite { implicit session =>
      val now = clock.now
      val libsById = libRepo.getActiveByIds(libIds)
      val superFastOrgs = orgExperimentRepo.getOrganizationsByExperiment(OrganizationExperimentType.SLACK_COMMENT_MIRRORING).toSet
      val (libsToPushImmediately, libsToPushSoon) = libIds.partition { libId =>
        libsById.get(libId).exists(_.organizationId.exists(superFastOrgs.contains))
      }
      libsToPushImmediately.foreach { libId => pushLibraryAtLatest(libId, now) }
      libsToPushSoon.foreach { libId => pushLibraryAtLatest(libId, now plus delayFromPushRequest) }

      libsToPushImmediately.nonEmpty
    }
    if (weShouldPushImmediately) pushingActor.ref ! IfYouCouldJustGoAhead
  }

  private def processIntegrations(integrationsToProcess: Seq[LibraryToSlackChannel]): Future[Map[Id[LibraryToSlackChannel], Boolean]] = {
    FutureHelpers.accumulateRobustly(integrationsToProcess)(pushUpdatesForIntegration).imap { results =>
      log.info(s"[SLACK-PUSH] Processed ${integrationsToProcess.map(_.id.get)}")
      results.collect {
        // PSA: exceptions inside of Futures are sometimes wrapped in this obnoxious ExecutionException box,
        // and that swallows the stack trace. This hack will manually expose the stack trace
        case (lib, Failure(boxFail: java.util.concurrent.ExecutionException)) =>
          airbrake.notify(boxFail.getCause.getStackTrace.toList.mkString("\n"))
        case (lib, Failure(fail)) =>
          airbrake.notify(s"Pushing slack updates to library $lib failed because of $fail")
      }
      results.collect {
        case (k, Success(v)) =>
          slackAnalytics.trackNotificationSent(k.slackTeamId, k.slackChannelId, k.slackChannelName, NotificationCategory.NonUser.NEW_KEEP)
          k.id.get -> v
      }
    }
  }

  private def getIntegrations(integrationIds: Seq[Id[LibraryToSlackChannel]])(implicit session: RWSession): Seq[LibraryToSlackChannel] = {
    val integrationsById = libToChannelRepo.getByIds(integrationIds.toSet)
    integrationIds.map(integrationsById(_))
  }

  private def markLegalIntegrationsForProcessing(integrationIds: Seq[Id[LibraryToSlackChannel]])(implicit session: RWSession): Seq[LibraryToSlackChannel] = {

    def isLegal(lts: LibraryToSlackChannel) = {
      slackTeamMembershipRepo.getBySlackTeamAndUser(lts.slackTeamId, lts.slackUserId).exists { stm =>
        val permissions = permissionCommander.getLibraryPermissions(lts.libraryId, stm.userId)
        permissions.contains(LibraryPermission.VIEW_LIBRARY)
      }
    }

    val (legal, illegal) = getIntegrations(integrationIds).partition(isLegal)
    illegal.foreach(lts => libToChannelRepo.save(lts.withStatus(SlackIntegrationStatus.Off)))

    val processingIntegrationIds = legal.map(_.id.get).filter(libToChannelRepo.markAsPushing(_, maxAcceptableProcessingDuration))
    val integrations = getIntegrations(processingIntegrationIds)
    integrations
  }

  def pushLibraryAtLatest(libId: Id[Library], when: DateTime)(implicit session: RWSession): Unit = {
    libToChannelRepo.getActiveByLibrary(libId).filter(_.status == SlackIntegrationStatus.On).foreach { lts =>
      val updatedLts = lts.withNextPushAtLatest(when)
      if (updatedLts != lts) libToChannelRepo.save(updatedLts)
    }
  }

  private def getUser(id: Id[User])(implicit session: RSession): BasicUser = basicUserRepo.load(id)

  private def keepAsDescriptionElements(keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution], messageCount: Int)(implicit session: RSession): DescriptionElements = {
    import DescriptionElements._

    val slackMessageOpt = attribution.collect { case sa: SlackAttribution => sa.message }

    val keepLink = LinkElement(pathCommander.keepPageOnUrlViaSlack(keep, slackTeamId).absolute)
    val libLink = LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeamId).absolute)
    val addComment = {
      val text = s"${SlackEmoji.speechBalloon.value} " + (if (messageCount > 1) s"$messageCount comments" else if (messageCount == 1) s"$messageCount comment" else "Reply")
      DescriptionElements("\n", text --> LinkElement(pathCommander.keepPageOnKifiViaSlack(keep, slackTeamId)))
    }

    slackMessageOpt match {
      case Some(message) =>
        val origin = message.channel.name match {
          case Some(prettyChannelName) => s"#${prettyChannelName.value}"
          case None => s"@${message.username.value}"
        }
        DescriptionElements(
          keep.title.getOrElse(keep.url.abbreviate(KEEP_URL_MAX_DISPLAY_LENGTH)) --> keepLink,
          "from", origin --> LinkElement(message.permalink),
          "was added to", lib.name --> libLink,
          addComment
        )
      case None =>
        val userElement: Option[DescriptionElements] = {
          val basicUserOpt = keep.userId.map(getUser)
          basicUserOpt.map(basicUser => basicUser.firstName --> LinkElement(pathCommander.userPageViaSlack(basicUser, slackTeamId)))
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
          addComment
        )
    }
  }

  private def describeKeeps(keeps: KeepsToPush): Option[Future[SlackMessageRequest]] = {
    import DescriptionElements._
    keeps match {
      case NoKeeps => None
      case SomeKeeps(ks, lib, slackTeamId, attribution) =>
        val slackMsgFut = eliza.getMessageCountsForKeeps(ks.map(_.id.get).toSet).map { countByKeep =>
          val msgs = db.readOnlyMaster { implicit s =>
            ks.map(k => keepAsDescriptionElements(k, lib, slackTeamId, attribution.get(k.id.get), countByKeep.getOrElse(k.id.get, 0)))
          }
          SlackMessageRequest.fromKifi(
            DescriptionElements.formatForSlack(DescriptionElements.unlines(msgs))
          )
        }
        Some(slackMsgFut)
      case ManyKeeps(ks, lib, slackTeamId, attribution) =>
        val msg = DescriptionElements(
          ks.length, "keeps have been added to",
          lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeamId))
        )
        Some(Future.successful(SlackMessageRequest.fromKifi(
          DescriptionElements.formatForSlack(msg)
        )))
    }
  }

  sealed abstract class KeepsToPush
  case object NoKeeps extends KeepsToPush
  case class SomeKeeps(keeps: Seq[Keep], lib: Library, slackTeamId: SlackTeamId, attribution: Map[Id[Keep], SourceAttribution]) extends KeepsToPush
  case class ManyKeeps(keeps: Seq[Keep], lib: Library, slackTeamId: SlackTeamId, attribution: Map[Id[Keep], SourceAttribution]) extends KeepsToPush

  private def pushUpdatesForIntegration(lts: LibraryToSlackChannel): Future[Boolean] = {
    val (keepsToPush, lastKtlOpt, mayHaveMore) = db.readOnlyReplica { implicit s => getKeepsToPushForIntegration(lts) }

    val hasBeenPushed = describeKeeps(keepsToPush) match {
      case None => Future.successful(true)
      case Some(futureMessage) => futureMessage.flatMap { message =>
        slackClient.sendToSlackViaUser(lts.slackUserId, lts.slackTeamId, lts.slackChannelId, message.quiet).imap(_ => true)
          .recover {
            case f: SlackFail =>
              log.info(s"[LTSCP] Failed to push Slack messages for integration ${lts.id.get} because $f")
              SafeFuture {
                val team = db.readOnlyReplica { implicit s =>
                  slackTeamRepo.getBySlackTeamId(lts.slackTeamId)
                }
                val org = db.readOnlyMaster { implicit s =>
                  team.flatMap(_.organizationId).flatMap(organizationInfoCommander.getBasicOrganizationHelper)
                }
                val name = team.map(_.slackTeamName.value).getOrElse("???")
                inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
                  "Can't push updates - Broke Slack integration of team", name, "and Kifi org", org, "channel", lts.slackChannelName.value, "cause", f.toString)))
              }
              false
          }
      }
    }

    hasBeenPushed.andThen {
      case Success(false) => db.readWrite { implicit s =>
        libToChannelRepo.save(lts.withStatus(SlackIntegrationStatus.Broken))
      }
      case maybeFail => db.readWrite { implicit s =>
        // To make sure we don't spam a channel, we ALWAYS register a push as "processed"
        // Sometimes this may lead to Slack messages getting lost
        // This is a better alternative than spamming a channel because Slack (or our HttpClient)
        // keeps saying we aren't succeeding even though the channel is getting messages
        libToChannelRepo.save(
          lts
            .finishedProcessing(lastKtlOpt)
            .withNextPushAt(clock.now plus (if (mayHaveMore) delayFromPartialPush else delayFromFullPush))
        )
        maybeFail match {
          case Failure(f) => airbrake.notify(f)
          case _ =>
        }
      }
    }
  }

  private def getKeepsToPushForIntegration(lts: LibraryToSlackChannel)(implicit session: RSession): (KeepsToPush, Option[KeepToLibrary], Boolean) = {
    val (keeps, lib, lastKtlOpt, mayHaveMore) = {
      val now = clock.now
      val lib = libRepo.get(lts.libraryId)
      val recentKtls = ktlRepo.getByLibraryAddedAfter(lts.libraryId, lts.lastProcessedKeep)
      val keepsByIds = keepRepo.getByIds(recentKtls.map(_.keepId).toSet)
      recentKtls.flatMap(ktl => keepsByIds.get(ktl.keepId).map(_ -> ktl)).takeWhile {
        case (keep, _) =>
          // A keep is fine to push if:
          //     It has a good title and is stable (i.e., it hasn't been updated in the last `n` seconds)
          //         OR
          //     It's been long enough that we're tired of waiting for it to become good/stable
          //         OR
          //     It's not from a human being (i.e., the keep source is not manual)
          val isTitleGoodEnough = keep.title.isDefined || (keep.keptAt isBefore (now minus maxTitleDelayFromKept))
          val isKeepStable = keep.updatedAt isBefore (now minus delayFromUpdatedAt)
          (isTitleGoodEnough && isKeepStable) || (keep.keptAt isBefore (now minus maxDelayFromKeptAt)) || !KeepSource.manual.contains(keep.source)
      } unzip match {
        case (stableRecentKeeps, stableRecentKtls) =>
          (stableRecentKeeps, lib, stableRecentKtls.lastOption, stableRecentKtls.length < recentKtls.length)
      }
    }
    val attributionByKeepId = keepSourceAttributionRepo.getByKeepIds(keeps.flatMap(_.id).toSet)
    def comesFromDestinationChannel(keepId: Id[Keep]): Boolean = attributionByKeepId.get(keepId).exists {
      case sa: SlackAttribution => lts.slackChannelId == sa.message.channel.id
      case _ => false
    }
    val relevantKeeps = keeps.filter(keep => !comesFromDestinationChannel(keep.id.get))
    val keepsToPush: KeepsToPush = relevantKeeps match {
      case Seq() => NoKeeps
      case ks if ks.length <= MAX_KEEPS_TO_SEND => SomeKeeps(ks, lib, lts.slackTeamId, attributionByKeepId)
      case ks => ManyKeeps(ks, lib, lts.slackTeamId, attributionByKeepId)
    }
    (keepsToPush, lastKtlOpt, mayHaveMore)
  }
}
