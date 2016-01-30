package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core.futureExtensionOps
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance.{ AlertingTimer, StatsdTimingAsync }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.strings._
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.common.util.{ LinkElement, DescriptionElements }
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.social.BasicUser
import org.joda.time.{ DateTime, Period }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object LibraryToSlackChannelPusher {
  val maxAcceptableProcessingDuration = Period.minutes(10) // we will wait this long for a process to complete before we assume it is incompetent
  val delayFromFullPush = Period.minutes(30)
  val delayFromPartialPush = Period.seconds(15)
  val delayFromPushRequest = Period.seconds(20)
  val maxTitleDelayFromKept = Period.seconds(40)
  val maxDelayFromKeptAt = Period.minutes(5)
  val delayFromUpdatedAt = Period.seconds(15)
  val MAX_KEEPS_TO_SEND = 7
  val INTEGRATIONS_BATCH_SIZE = 100
  val KEEP_URL_MAX_DISPLAY_LENGTH = 60
}

@ImplementedBy(classOf[LibraryToSlackChannelPusherImpl])
trait LibraryToSlackChannelPusher {
  // Method called by scheduled plugin
  def findAndPushUpdatesForRipestIntegrations(): Future[Map[Id[LibraryToSlackChannel], Boolean]]

  // Method to be called if something happens in a library
  def schedule(libId: Id[Library]): Unit
}

@Singleton
class LibraryToSlackChannelPusherImpl @Inject() (
  db: Database,
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
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext)
    extends LibraryToSlackChannelPusher with Logging {

  import LibraryToSlackChannelPusher._

  @StatsdTimingAsync("LibraryToSlackChannelPusher.findAndPushUpdatesForRipestLibraries")
  @AlertingTimer(5 seconds)
  def findAndPushUpdatesForRipestIntegrations(): Future[Map[Id[LibraryToSlackChannel], Boolean]] = {
    val integrationsToProcess = db.readWrite { implicit s =>
      val integrations = libToChannelRepo.getIntegrationsRipeForProcessing(Limit(INTEGRATIONS_BATCH_SIZE), overrideProcessesOlderThan = clock.now minus maxAcceptableProcessingDuration)
      markLegalIntegrationsForProcessing(integrations)
    }
    processIntegrations(integrationsToProcess)
  }

  def schedule(libId: Id[Library]): Unit = db.readWrite { implicit session =>
    val nextPushAt = clock.now plus delayFromPushRequest
    pushLibraryAtLatest(libId, nextPushAt)
  }

  private def processIntegrations(integrationsToProcess: Seq[LibraryToSlackChannel]): Future[Map[Id[LibraryToSlackChannel], Boolean]] = {
    FutureHelpers.accumulateRobustly(integrationsToProcess)(pushUpdatesForIntegration).imap { results =>
      results.collect {
        // PSA: exceptions inside of Futures are sometimes wrapped in this obnoxious ExecutionException box,
        // and that swallows the stack trace. This hack will manually expose the stack trace
        case (lib, Failure(boxFail: java.util.concurrent.ExecutionException)) =>
          airbrake.notify(boxFail.getCause.getStackTrace.toList.mkString("\n"))
        case (lib, Failure(fail)) =>
          airbrake.notify(s"Pushing slack updates to library $lib failed because of $fail")
      }
      results.collect { case (k, Success(v)) => k.id.get -> v }
    }
  }

  private def markLegalIntegrationsForProcessing(integrations: Seq[LibraryToSlackChannel])(implicit session: RWSession): Seq[LibraryToSlackChannel] = {
    def isLegal(lts: LibraryToSlackChannel) = {
      slackTeamMembershipRepo.getBySlackTeamAndUser(lts.slackTeamId, lts.slackUserId).exists { stm =>
        permissionCommander.getLibraryPermissions(lts.libraryId, stm.userId).contains(LibraryPermission.VIEW_LIBRARY)
      }
    }
    val (legal, illegal) = integrations.partition(isLegal)
    illegal.foreach(lts => libToChannelRepo.save(lts.withStatus(SlackIntegrationStatus.Off)))

    legal.flatMap(lts => libToChannelRepo.markAsProcessing(lts.id.get))
  }

  def pushLibraryAtLatest(libId: Id[Library], when: DateTime)(implicit session: RWSession): Unit = {
    libToChannelRepo.getActiveByLibrary(libId).filter(_.status == SlackIntegrationStatus.On).foreach { lts =>
      val updatedLts = lts.withNextPushAtLatest(when)
      if (updatedLts != lts) libToChannelRepo.save(updatedLts)
    }
  }

  private def getUser(id: Id[User])(implicit session: RSession): BasicUser = basicUserRepo.load(id)

  private def keepAsDescriptionElements(keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution])(implicit session: RSession): DescriptionElements = {
    import DescriptionElements._

    val slackMessageOpt = attribution.collect {
      case sa: SlackAttribution => sa.message
    }

    slackMessageOpt match {
      case Some(post) =>
        DescriptionElements(
          keep.title.getOrElse(keep.url.abbreviate(KEEP_URL_MAX_DISPLAY_LENGTH)) --> LinkElement(keep.url), "from", s"#${post.channel.name.value}" --> LinkElement(post.permalink),
          "was added to", lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeamId).absolute), ".", "Add Comment" --> LinkElement(pathCommander.keepPageOnKifiViaSlack(keep, slackTeamId))
        )
      case None =>
        DescriptionElements(
          getUser(keep.userId), "added",
          keep.title.getOrElse(keep.url.abbreviate(KEEP_URL_MAX_DISPLAY_LENGTH)) --> LinkElement(pathCommander.keepPageOnUrlViaSlack(keep, slackTeamId)),
          "to", lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeamId).absolute),
          keep.note.map(n => DescriptionElements("—", "_“", Hashtags.format(n), "”_")), ".", "Add Comment" --> LinkElement(pathCommander.keepPageOnKifiViaSlack(keep, slackTeamId))
        )
    }
  }

  private def describeKeeps(keeps: KeepsToPush): Option[Future[SlackMessageRequest]] = {
    import DescriptionElements._
    keeps match {
      case NoKeeps => None
      case SomeKeeps(ks, lib, slackTeamId, attribution) =>
        val msgs = db.readOnlyMaster { implicit s =>
          ks.map(k => keepAsDescriptionElements(k, lib, slackTeamId, attribution.get(k.id.get)))
        }
        Some(Future.successful(SlackMessageRequest.fromKifi(
          DescriptionElements.formatForSlack(DescriptionElements.unlines(msgs))
        )))
      case ManyKeeps(ks, lib, slackTeamId, attribution) =>
        val msg = DescriptionElements(
          ks.length, "keeps have been added to",
          lib.name --> LinkElement(pathCommander.libraryPageViaSlack(lib, slackTeamId).absolute)
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
    val (keepsToPush, lastKtlIdOpt, mayHaveMore) = db.readOnlyReplica { implicit s => getKeepsToPushForIntegration(lts) }

    val hasBeenPushed = describeKeeps(keepsToPush) match {
      case None => Future.successful(true)
      case Some(futureMessage) => futureMessage.flatMap { message =>
        slackClient.sendToSlack(lts.slackUserId, lts.slackTeamId, lts.channel, message.quiet).imap(_ => true)
          .recover {
            case f: SlackAPIFailure =>
              log.info(s"[LTSCP] Failed to push Slack messages for integration ${lts.id.get} because $f")
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
            .finishedProcessing
            .withLastProcessedKeep(lastKtlIdOpt orElse lts.lastProcessedKeep)
            .withNextPushAt(clock.now plus (if (mayHaveMore) delayFromPartialPush else delayFromFullPush))
        )
        maybeFail match {
          case Failure(f) => airbrake.notify(f)
          case _ =>
        }
      }
    }
  }

  private def getKeepsToPushForIntegration(lts: LibraryToSlackChannel)(implicit session: RSession): (KeepsToPush, Option[Id[KeepToLibrary]], Boolean) = {
    val (keeps, lib, lastKtlIdOpt, mayHaveMore) = {
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
          (stableRecentKeeps, lib, stableRecentKtls.lastOption.map(_.id.get), stableRecentKtls.length < recentKtls.length)
      }
    }
    val attributionByKeepId = keepSourceAttributionRepo.getByKeepIds(keeps.flatMap(_.id).toSet)
    def comesFromDestinationChannel(keepId: Id[Keep]): Boolean = attributionByKeepId.get(keepId).exists {
      case sa: SlackAttribution => lts.slackChannelId.contains(sa.message.channel.id)
      case _ => false
    }
    val relevantKeeps = keeps.filter(keep => !comesFromDestinationChannel(keep.id.get))
    val keepsToPush: KeepsToPush = relevantKeeps match {
      case Seq() => NoKeeps
      case ks if ks.length <= MAX_KEEPS_TO_SEND => SomeKeeps(ks, lib, lts.slackTeamId, attributionByKeepId)
      case ks => ManyKeeps(ks, lib, lts.slackTeamId, attributionByKeepId)
    }
    (keepsToPush, lastKtlIdOpt, mayHaveMore)
  }
}
