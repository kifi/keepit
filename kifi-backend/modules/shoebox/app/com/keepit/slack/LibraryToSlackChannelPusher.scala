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
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  slackClient: SlackClient,
  libToChannelRepo: LibraryToSlackChannelRepo,
  clock: Clock,
  ktlRepo: KeepToLibraryRepo,
  keepRepo: KeepRepo,
  basicUserRepo: BasicUserRepo,
  pathCommander: PathCommander,
  keepDecorator: KeepDecorator,
  implicit val executionContext: ExecutionContext)
    extends LibraryToSlackChannelPusher with Logging {

  private val gracePeriod = Period.minutes(10) // we will wait this long for a process to complete before we assume it is incompetent
  private val MAX_KEEPS_TO_SEND = 4 // NB: if you change this, change `numberToWords` below as well
  def findAndPushToLibraries(): Unit = {
    val librariesThatNeedToBeProcessed = db.readOnlyReplica { implicit s =>
      libToChannelRepo.getLibrariesRipeForProcessing(Limit(10), overrideProcessesOlderThan = clock.now.minus(gracePeriod))
    }
    librariesThatNeedToBeProcessed.foreach(pushToLibrary)
  }

  private def getUser(id: Id[User])(implicit session: RSession): BasicUser = basicUserRepo.load(id)
  private def describeKeep(keep: Keep, summary: URISummary): SlackAttachment = {
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
  private def numberToWords(n: Int): String = n match {
    case 1 => "One keep was"
    case 2 => "Two keeps were"
    case 3 => "Three keeps were"
    case 4 => "Four keeps were"
  }
  def pushToLibrary(libId: Id[Library]): Future[Map[Id[LibraryToSlackChannel], Boolean]] = {
    import com.keepit.payments.DescriptionElements._
    val (lib, integrationsToProcess) = db.readWrite { implicit s =>
      val lib = libRepo.get(libId)
      val integrations = libToChannelRepo.getIntegrationsRipeForProcessingByLibrary(libId, overrideProcessesOlderThan = clock.now.minus(gracePeriod))
      val integrationsToProcess = integrations.flatMap(ltsId => libToChannelRepo.markAsProcessing(ltsId))
      (lib, integrationsToProcess)
    }
    val slackPushes = integrationsToProcess.map { lts =>
      val (webhookOpt, ktls, keeps) = db.readOnlyReplica { implicit s =>
        val webhook = slackIncomingWebhookInfoRepo.getByIntegration(lts)

        val ktls = ktlRepo.getByLibraryFromTimeAndId(libId, lts.lastProcessedAt, lts.lastProcessedKeep)
        val keepsById = keepRepo.getByIds(ktls.map(_.keepId).toSet)
        (webhook, ktls, ktls.flatMap(ktl => keepsById.get(ktl.keepId)))
      }
      val (messageOpt, attachmentsFut) = ktls.length match {
        case noKeeps if noKeeps == 0 =>
          (None, Future.successful(Seq.empty))
        case lotsOfKeeps if lotsOfKeeps > MAX_KEEPS_TO_SEND =>
          val msg = DescriptionElements(lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "has", ktls.length, "new keeps.")
          (Some(msg), Future.successful(Seq.empty))
        case justAFewKeeps if 1 <= justAFewKeeps && justAFewKeeps <= MAX_KEEPS_TO_SEND =>
          val msg = DescriptionElements(numberToWords(ktls.length), "added to the", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "library.")
          val summariesFut = keepDecorator.getKeepSummaries(keeps, ProcessedImageSize.Small.idealSize)
          val attachments = summariesFut.map { summaries =>
            (keeps zip summaries).map {
              case (keep, summary) => describeKeep(keep, summary)
            }
          }
          (Some(msg), attachments)
      }

      val slackPush = (webhookOpt, messageOpt) match {
        case (None, _) => Future.successful(false)
        case (Some(wh), None) => Future.successful(true)
        case (Some(wh), Some(msg)) =>
          attachmentsFut.flatMap { attachments =>
            slackClient.sendToSlack(wh.webhook.url, SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(msg)).quiet.withAttachments(attachments))
              .imap { _ => true }
              .recover {
                case f: SlackAPIFailure =>
                  log.info(s"[LTSCP] Failed to push Slack messages for integration ${lts.id.get} because $f")
                  false
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
