package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core.futureExtensionOps
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance.{ AlertingTimer, StatsdTiming }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.social.BasicUser
import org.joda.time.{ DateTime, Period }
import com.keepit.common.strings._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Try, Success }

object LibraryToSlackChannelPusher {
  val maxAcceptableProcessingDuration = Period.minutes(10) // we will wait this long for a process to complete before we assume it is incompetent
  val delayFromFullPush = Period.minutes(30)
  val delayFromPartialPush = Period.seconds(15)
  val delayFromPushRequest = Period.seconds(20)
  val maxTitleDelayFromKept = Period.seconds(40)
  val maxDelayFromKeptAt = Period.minutes(5)
  val delayFromUpdatedAt = Period.seconds(15)
  val MAX_KEEPS_TO_SEND = 7
  val LIBRARY_BATCH_SIZE = 20
  val KEEP_URL_MAX_DISPLAY_LENGTH = 60
}

@ImplementedBy(classOf[LibraryToSlackChannelPusherImpl])
trait LibraryToSlackChannelPusher {
  // Method called by scheduled plugin
  def findAndPushUpdatesForRipestLibraries(): Future[Unit]

  // Method to be called if something happens in a library
  def schedule(libId: Id[Library]): Unit

  // Only call there are scheduled pushes that you want to process immediately
  def pushUpdatesToSlack(libId: Id[Library]): Future[Map[Id[LibraryToSlackChannel], Boolean]]
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

  @StatsdTiming("LibraryToSlackChannelPusher.findAndPushUpdatesForRipestLibraries")
  @AlertingTimer(5 seconds)
  def findAndPushUpdatesForRipestLibraries(): Future[Unit] = {
    val librariesThatNeedToBeProcessed = db.readOnlyReplica { implicit s =>
      libToChannelRepo.getLibrariesRipeForProcessing(Limit(LIBRARY_BATCH_SIZE), overrideProcessesOlderThan = clock.now minus maxAcceptableProcessingDuration)
    }
    FutureHelpers.sequentialExec(librariesThatNeedToBeProcessed)(pushUpdatesToSlack).recoverWith {
      // PSA: exceptions inside of Futures are sometimes wrapped in this obnoxious ExecutionException box,
      // and that swallows the stack trace. This hack will manually expose the stack trace
      case boxFail: java.util.concurrent.ExecutionException => Future.failed(new Exception(boxFail.getCause.getStackTrace.toList.mkString("\n")))
    }.recover {
      case fail => airbrake.notify(s"Pushing slack updates to ripest libraries failed because of $fail")
    }
  }

  def schedule(libId: Id[Library]): Unit = db.readWrite { implicit session =>
    val nextPushAt = clock.now plus delayFromPushRequest
    pushLibraryAtLatest(libId, nextPushAt)
  }

  def pushLibraryAtLatest(libId: Id[Library], when: DateTime)(implicit session: RWSession): Unit = {
    libToChannelRepo.getActiveByLibrary(libId).foreach { lts =>
      val updatedLts = lts.withNextPushAtLatest(when)
      if (updatedLts != lts) libToChannelRepo.save(updatedLts)
    }
  }

  private def getUser(id: Id[User])(implicit session: RSession): BasicUser = basicUserRepo.load(id)
  // TODO(ryan): if this hasn't been used by 2016-02-01, kill it.
  private def keepAsSlackAttachment(keep: Keep, summary: URISummary): SlackAttachment = {
    val title = summary.title.orElse(keep.title).getOrElse(keep.url.abbreviate(KEEP_URL_MAX_DISPLAY_LENGTH))
    SlackAttachment(
      title = Some(SlackAttachment.Title(title, Some(keep.url))),
      text = summary.description,
      imageUrl = summary.imageUrl,
      fallback = Some(title),
      color = None,
      pretext = None,
      author = None,
      fields = Seq.empty,
      thumbUrl = None,
      service = None,
      fromUrl = None
    )
  }
  private def keepAsDescriptionElements(keep: Keep, lib: Library, attribution: Option[SourceAttribution])(implicit session: RSession): DescriptionElements = {
    import DescriptionElements._

    val slackMessageOpt = attribution.collect {
      case sa: SlackAttribution => sa.message
    }

    slackMessageOpt match {
      case Some(post) =>
        DescriptionElements(
          keep.title.getOrElse(keep.url.abbreviate(KEEP_URL_MAX_DISPLAY_LENGTH)) --> LinkElement(keep.url), "from", s"#${post.channel.name.value}" --> LinkElement(post.permalink),
          "was added to", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "."
        )
      case None =>
        DescriptionElements(
          getUser(keep.userId), "added", keep.title.getOrElse(keep.url.abbreviate(KEEP_URL_MAX_DISPLAY_LENGTH)) --> LinkElement(keep.url),
          "to", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), keep.note.map(n => DescriptionElements("—", "_“", Hashtags.format(n), "”_")), "."
        )
    }
  }
  private def describeKeeps(keeps: KeepsToPush, withAttachments: Boolean = false): Option[Future[SlackMessageRequest]] = {
    import DescriptionElements._
    keeps match {
      case NoKeeps =>
        None
      case SomeKeeps(ks, lib, attribution) if withAttachments =>
        val msg = DescriptionElements(ks.length, "keeps have been added to", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute))
        val summariesFut = keepDecorator.getKeepSummaries(ks, ProcessedImageSize.Small.idealSize)
        Some(summariesFut.map { summaries =>
          val attachments = (ks zip summaries).map {
            case (keep, summary) => keepAsSlackAttachment(keep, summary)
          }
          SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(msg), attachments = attachments)
        })
      case SomeKeeps(ks, lib, attribution) if !withAttachments =>
        val msgs = db.readOnlyMaster { implicit s =>
          ks.map(k => keepAsDescriptionElements(k, lib, attribution.get(k.id.get)))
        }
        Some(Future.successful(SlackMessageRequest.fromKifi(
          DescriptionElements.formatForSlack(DescriptionElements.unlines(msgs))
        )))
      case ManyKeeps(ks, lib, attribution) =>
        val msg = DescriptionElements(ks.length, "keeps have been added to", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute))
        Some(Future.successful(SlackMessageRequest.fromKifi(
          DescriptionElements.formatForSlack(msg)
        )))
    }
  }

  sealed abstract class KeepsToPush
  case object NoKeeps extends KeepsToPush
  case class SomeKeeps(keeps: Seq[Keep], lib: Library, attribution: Map[Id[Keep], SourceAttribution]) extends KeepsToPush
  case class ManyKeeps(keeps: Seq[Keep], lib: Library, attribution: Map[Id[Keep], SourceAttribution]) extends KeepsToPush

  def pushUpdatesToSlack(libId: Id[Library]): Future[Map[Id[LibraryToSlackChannel], Boolean]] = {
    log.info(s"[LTSCP] Processing $libId")
    db.readWriteAsync { implicit s =>
      val lib = libRepo.get(libId)
      val integrations = libToChannelRepo.getIntegrationsRipeForProcessingByLibrary(libId, overrideProcessesOlderThan = clock.now minus maxAcceptableProcessingDuration)

      def isIllegal(lts: LibraryToSlackChannel) = {
        slackTeamMembershipRepo.getBySlackTeamAndUser(lts.slackTeamId, lts.slackUserId).exists { stm =>
          !permissionCommander.getLibraryPermissions(libId, Some(stm.userId)).contains(LibraryPermission.VIEW_LIBRARY)
        }
      }
      integrations.map(libToChannelRepo.get).filter(isIllegal).foreach(lts => libToChannelRepo.save(lts.withStatus(SlackIntegrationStatus.Off)))

      val integrationsToProcess = integrations.flatMap(ltsId => libToChannelRepo.markAsProcessing(ltsId))
      (lib, integrationsToProcess)
    }.flatMap {
      case (lib, integrationsToProcess) => FutureHelpers.accumulateOneAtATime(integrationsToProcess.toSet) { lts =>
        val (webhookOpt, keepsToPush, lastKtlIdOpt, mayHaveMore) = db.readOnlyReplica { implicit s =>
          val webhook = slackIncomingWebhookInfoRepo.getForIntegration(lts)
          val (keeps, lastKtlIdOpt, mayHaveMore) = {
            val recentKtls = ktlRepo.getByLibraryAddedAfter(libId, lts.lastProcessedKeep)
            val keepsByIds = keepRepo.getByIds(recentKtls.map(_.keepId).toSet)
            val now = clock.now()
            recentKtls.flatMap(ktl => keepsByIds.get(ktl.keepId).map(_ -> ktl)).takeWhile {
              case (keep, _) =>
                val isTitleGoodEnough = keep.title.isDefined || (keep.keptAt isBefore (now minus maxTitleDelayFromKept))
                val isKeepStable = keep.updatedAt isBefore (now minus delayFromUpdatedAt)
                (isTitleGoodEnough && isKeepStable) || (keep.keptAt isBefore (now minus maxDelayFromKeptAt))
            } unzip match { case (stableRecentKeeps, stableRecentKtls) => (stableRecentKeeps, stableRecentKtls.lastOption.map(_.id.get), stableRecentKtls.length < recentKtls.length) }
          }
          val attributionByKeepId = keepSourceAttributionRepo.getByKeepIds(keeps.flatMap(_.id).toSet)
          def comesFromDestinationChannel(keepId: Id[Keep]): Boolean = attributionByKeepId.get(keepId).exists {
            case sa: SlackAttribution => lts.slackChannelId.contains(sa.message.channel.id)
            case _ => false
          }
          val relevantKeeps = keeps.filter(keep => !comesFromDestinationChannel(keep.id.get))
          val keepsToPush: KeepsToPush = relevantKeeps match {
            case Seq() => NoKeeps
            case ks if ks.length <= MAX_KEEPS_TO_SEND => SomeKeeps(ks, lib, attributionByKeepId)
            case ks => ManyKeeps(ks, lib, attributionByKeepId)
          }
          (webhook, keepsToPush, lastKtlIdOpt, mayHaveMore)
        }

        val slackPush = webhookOpt match {
          case None => Future.successful(false)
          case Some(wh) =>
            describeKeeps(keepsToPush) match {
              case None => Future.successful(true)
              case Some(msgFut) =>
                msgFut.flatMap { msg =>
                  slackClient.sendToSlack(wh.webhook, msg.quiet).imap { _ => true }
                    .recover {
                      case f: SlackAPIFailure =>
                        log.info(s"[LTSCP] Failed to push Slack messages for integration ${lts.id.get} because $f")
                        false
                    }
                }
            }
        }
        slackPush.andThen {
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
      }.imap { result => result.map { case (lts, b) => lts.id.get -> b } }
    }
  }
}
