package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.{ KeepDecorator, PathCommander, PermissionCommander, ProcessedImageSize }
import com.keepit.common.core.futureExtensionOps
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.NonOKResponseException
import com.keepit.common.performance.{ AlertingTimer, StatsdTiming }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.social.BasicUser
import org.joda.time.{ DateTime, Period }

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.DurationConversions
import scala.util.Success

@ImplementedBy(classOf[LibraryToSlackChannelPusherImpl])
trait LibraryToSlackChannelPusher {
  // Method called by scheduled plugin
  def findAndPushUpdatesForRipestLibraries(): Unit

  // Method to be called if something happens in a library, meant to be called from within other db sessions
  def scheduleLibraryToBePushed(libId: Id[Library], when: DateTime)(implicit session: RWSession): Unit

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

  private val maxAcceptableProcessingDuration = Period.minutes(10) // we will wait this long for a process to complete before we assume it is incompetent
  private val defaultDelayBetweenPushes = Period.minutes(30)
  private val MAX_KEEPS_TO_SEND = 7
  private val LIBRARY_BATCH_SIZE = 20

  @StatsdTiming("LibraryToSlackChannelPusher.findAndPushUpdatesForRipestLibraries")
  @AlertingTimer(5 seconds)
  def findAndPushUpdatesForRipestLibraries(): Unit = {
    val librariesThatNeedToBeProcessed = db.readOnlyReplica { implicit s =>
      libToChannelRepo.getLibrariesRipeForProcessing(Limit(LIBRARY_BATCH_SIZE), overrideProcessesOlderThan = clock.now minus maxAcceptableProcessingDuration)
    }
    librariesThatNeedToBeProcessed.foreach(pushUpdatesToSlack)
  }
  def scheduleLibraryToBePushed(libId: Id[Library], when: DateTime)(implicit session: RWSession): Unit = {
    libToChannelRepo.getActiveByLibrary(libId).foreach { lts =>
      libToChannelRepo.save(lts.withNextPushAtLatest(when))
    }
  }

  private def getUser(id: Id[User])(implicit session: RSession): BasicUser = basicUserRepo.load(id)
  // TODO(ryan): if this hasn't been used by 2016-02-01, kill it.
  private def keepAsSlackAttachment(keep: Keep, summary: URISummary): SlackAttachment = {
    val title = summary.title.orElse(keep.title).getOrElse(keep.url)
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
  private def keepAsDescriptionElements(keep: Keep, lib: Library)(implicit session: RSession): DescriptionElements = {
    import DescriptionElements._

    val slackMessageOpt = keep.sourceAttributionId.map(keepSourceAttributionRepo.get(_).attribution).collect {
      case sa: SlackAttribution => sa.message
    }

    slackMessageOpt match {
      case Some(post) =>
        DescriptionElements(
          keep.title.getOrElse("a link") --> LinkElement(keep.url), "from", s"#${post.channel.name.value}" --> LinkElement(post.permalink),
          "was added to", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "."
        )
      case None =>
        DescriptionElements(
          getUser(keep.userId), "added", keep.title.getOrElse("a keep") --> LinkElement(keep.url),
          "to", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "."
        )
    }
  }
  private def describeKeeps(keeps: KeepsToPush, withAttachments: Boolean = false): Option[Future[SlackMessageRequest]] = {
    import DescriptionElements._
    keeps match {
      case NoKeeps =>
        None
      case SomeKeeps(ks, lib) if withAttachments =>
        val msg = DescriptionElements(ks.length, "keeps have been added to", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute))
        val summariesFut = keepDecorator.getKeepSummaries(ks, ProcessedImageSize.Small.idealSize)
        Some(summariesFut.map { summaries =>
          val attachments = (ks zip summaries).map {
            case (keep, summary) => keepAsSlackAttachment(keep, summary)
          }
          SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(msg), attachments = attachments)
        })
      case SomeKeeps(ks, lib) if !withAttachments =>
        val msgs = db.readOnlyMaster { implicit s =>
          ks.map(k => keepAsDescriptionElements(k, lib))
        }
        Some(Future.successful(SlackMessageRequest.fromKifi(
          DescriptionElements.formatForSlack(DescriptionElements.unlines(msgs))
        )))
      case ManyKeeps(ks, lib) =>
        val msg = DescriptionElements(ks.length, "keeps have been added to", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute))
        Some(Future.successful(SlackMessageRequest.fromKifi(
          DescriptionElements.formatForSlack(msg)
        )))
    }
  }

  sealed abstract class KeepsToPush
  case object NoKeeps extends KeepsToPush
  case class SomeKeeps(keeps: Seq[Keep], lib: Library) extends KeepsToPush
  case class ManyKeeps(keeps: Seq[Keep], lib: Library) extends KeepsToPush

  def pushUpdatesToSlack(libId: Id[Library]): Future[Map[Id[LibraryToSlackChannel], Boolean]] = {
    val (lib, integrationsToProcess) = db.readWrite { implicit s =>
      val lib = libRepo.get(libId)
      val integrations = libToChannelRepo.getIntegrationsRipeForProcessingByLibrary(libId, overrideProcessesOlderThan = clock.now minus maxAcceptableProcessingDuration)

      def isIllegal(lts: LibraryToSlackChannel) = {
        !permissionCommander.getLibraryPermissions(libId, Some(lts.ownerId)).contains(LibraryPermission.VIEW_LIBRARY)
      }
      integrations.map(libToChannelRepo.get).filter(isIllegal).foreach(lts => libToChannelRepo.save(lts.withStatus(SlackIntegrationStatus.Off)))

      val integrationsToProcess = integrations.flatMap(ltsId => libToChannelRepo.markAsProcessing(ltsId))
      (lib, integrationsToProcess)
    }
    val slackPushes = integrationsToProcess.map { lts =>
      val (webhookOpt, ktls, keepsToPush) = db.readOnlyReplica { implicit s =>
        val webhook = slackIncomingWebhookInfoRepo.getByIntegration(lts)
        val ktls = ktlRepo.getByLibraryFromTimeAndId(libId, lts.lastProcessedAt, lts.lastProcessedKeep)
        val keepsById = keepRepo.getByIds(ktls.map(_.keepId).toSet)
        val keeps = ktls.flatMap(ktl => keepsById.get(ktl.keepId))
        val keepsToPush: KeepsToPush = keeps match {
          case Seq() => NoKeeps
          case ks if ks.length <= MAX_KEEPS_TO_SEND => SomeKeeps(ks, lib)
          case ks => ManyKeeps(ks, lib)
        }
        (webhook, ktls, keepsToPush)
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
        case _ => db.readWrite { implicit s =>
          // To make sure we don't spam a channel, we ALWAYS register a push as "processed"
          // Sometimes this may lead to Slack messages getting lost
          // This is a better alternative than spamming a channel because Slack (or our HttpClient)
          // keeps saying we aren't succeeding even though the channel is getting messages
          libToChannelRepo.save(
            lts
              .finishedProcessing
              .withLastProcessedKeep(ktls.lastOption.map(_.id.get))
              .withNextPushAt(clock.now plus defaultDelayBetweenPushes)
          )
        }
      }.imap { res => lts.id.get -> res }
    }
    Future.sequence(slackPushes).imap(_.toMap)
  }
}
