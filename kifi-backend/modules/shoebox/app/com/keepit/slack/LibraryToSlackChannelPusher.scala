package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.{ ProcessedImageSize, KeepDecorator, PathCommander }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.ImageSize
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.model._
import com.keepit.payments.{ LinkElement, DescriptionElements }
import com.keepit.slack.models._
import com.keepit.social.BasicUser
import org.joda.time.Period
import com.keepit.common.core.futureExtensionOps

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[LibraryToSlackChannelPusherImpl])
trait LibraryToSlackChannelPusher {
  def pushToLibrary(libId: Id[Library]): Future[Map[Id[LibraryToSlackChannel], Boolean]]
  def findAndPushToLibraries(): Unit
}

@Singleton
class LibraryToSlackChannelPusherImpl @Inject() (
  db: Database,
  libRepo: LibraryRepo,
  slackClient: SlackClientWrapper,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  clock: Clock,
  ktlRepo: KeepToLibraryRepo,
  keepRepo: KeepRepo,
  keepSourceAttributionRepo: KeepSourceAttributionRepo,
  basicUserRepo: BasicUserRepo,
  pathCommander: PathCommander,
  keepDecorator: KeepDecorator,
  implicit val executionContext: ExecutionContext)
    extends LibraryToSlackChannelPusher with Logging {

  private val gracePeriod = Period.minutes(10) // we will wait this long for a process to complete before we assume it is incompetent
  private val MAX_KEEPS_TO_SEND = 4
  def findAndPushToLibraries(): Unit = {
    val librariesThatNeedToBeProcessed = db.readOnlyReplica { implicit s =>
      libToChannelRepo.getLibrariesRipeForProcessing(Limit(10), overrideProcessesOlderThan = clock.now.minus(gracePeriod))
    }
    librariesThatNeedToBeProcessed.foreach(pushToLibrary)
  }

  private def getUser(id: Id[User])(implicit session: RSession): BasicUser = basicUserRepo.load(id)
  // TODO(ryan): if this hasn't been used by 2016-02-01, kill it.
  private def keepAsSlackAttachment(keep: Keep, summary: URISummary): SlackAttachment = {
    val title = summary.title.orElse(keep.title).getOrElse(keep.url)
    SlackAttachment(
      title = Some(SlackAttachment.Title(title, Some(keep.url))),
      text = summary.description,
      imageUrl = summary.imageUrl,
      fallback = title,
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
          s"@${post.username.value}", "posted", keep.title.getOrElse("a link") --> LinkElement(keep.url), "to", s"#${post.channel.name.value}" --> LinkElement(post.permalink), ".",
          "It was automatically added to the", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "library."
        )
      case None =>
        DescriptionElements(
          getUser(keep.userId), "added", keep.title.getOrElse("a keep") --> LinkElement(keep.url),
          "to the", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "library.")
    }
  }
  private def describeKeeps(keeps: KeepsToPush, withAttachments: Boolean = false): Option[Future[SlackMessageRequest]] = {
    import DescriptionElements._
    keeps match {
      case NoKeeps =>
        None
      case SomeKeeps(ks, lib) if withAttachments =>
        val msg = DescriptionElements("The", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "library has", ks.length, "new keeps")
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
        val msg = DescriptionElements("The", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "library has", ks.length, "new keeps")
        Some(Future.successful(SlackMessageRequest.fromKifi(
          DescriptionElements.formatForSlack(msg)
        )))
    }
  }

  sealed abstract class KeepsToPush
  case object NoKeeps extends KeepsToPush
  case class SomeKeeps(keeps: Seq[Keep], lib: Library) extends KeepsToPush
  case class ManyKeeps(keeps: Seq[Keep], lib: Library) extends KeepsToPush

  def pushToLibrary(libId: Id[Library]): Future[Map[Id[LibraryToSlackChannel], Boolean]] = {
    val (lib, integrationsToProcess) = db.readWrite { implicit s =>
      val lib = libRepo.get(libId)
      val integrations = libToChannelRepo.getIntegrationsRipeForProcessingByLibrary(libId, overrideProcessesOlderThan = clock.now.minus(gracePeriod))
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
        case res => db.readWrite { implicit s =>
          libToChannelRepo.finishProcessing(lts.withLastProcessedKeep(ktls.lastOption.map(_.id.get)))
        }
      }.imap { res => lts.id.get -> res }
    }
    Future.sequence(slackPushes).imap(_.toMap)
  }
}
