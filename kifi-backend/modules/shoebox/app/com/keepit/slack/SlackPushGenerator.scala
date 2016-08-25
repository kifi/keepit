package com.keepit.slack

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.core._
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.path.Path
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ S3ImageConfig, StaticImageUrls }
import com.keepit.common.strings._
import com.keepit.common.time._
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.discussion.{ CrossServiceMessage, Message }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.slack.SlackPushGenerator.PushItem.{ KeepToPush, MessageToPush }
import com.keepit.slack.models._
import com.keepit.social.BasicUser
import org.joda.time.DateTime

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object SlackPushGenerator {
  val MAX_ITEMS_TO_PUSH = 7
  val KEEP_TITLE_MAX_DISPLAY_LENGTH = 60

  val imageUrlRegex = """^https?://[^\s]*\.(png|jpg|jpeg|gif)""".r
  val jenUserId = ExternalId[User]("ae139ae4-49ad-4026-b215-1ece236f1322")

  // In the case of an exact tie on _.time: digests first, then keeps, then messages
  sealed abstract class PushItem(val time: DateTime, val userAttribution: Option[Id[User]], val nice: Int)
  object PushItem {
    case class Digest(since: DateTime) extends PushItem(since, None, 0)
    case class KeepToPush(k: Keep, ktl: KeepToLibrary) extends PushItem(ktl.addedAt, ktl.addedBy, 1)
    case class MessageToPush(k: Keep, msg: CrossServiceMessage) extends PushItem(msg.sentAt, msg.sentBy.flatMap(_.left.toOption), 2)
  }
  case class PushItems(
      oldKeeps: Seq[KeepToPush],
      newKeeps: Seq[KeepToPush],
      oldMsgs: Seq[MessageToPush],
      newMsgs: Seq[MessageToPush],
      lib: Library,
      slackTeamId: SlackTeamId,
      slackChannelId: SlackChannelId,
      attribution: Map[Id[Keep], SourceAttribution],
      users: Map[Id[User], BasicUser]) {
    def maxKeepSeq: Option[SequenceNumber[Keep]] = Seq(oldKeeps, newKeeps).flatMap(_.map(_.k.seq)).maxOpt
    def maxMsgSeq: Option[SequenceNumber[Message]] = Seq(oldMsgs, newMsgs).flatMap(_.map(_.msg.seq)).maxOpt
    def sortedNewItems: Seq[PushItem] = {
      val items: Seq[PushItem] = newKeeps ++ newMsgs
      if (items.length > MAX_ITEMS_TO_PUSH) Seq(PushItem.Digest(newKeeps.map(_.ktl.addedAt).minOpt getOrElse newMsgs.map(_.msg.sentAt).min))
      else items.sortBy(i => (i.time, i.nice))
    }
  }

  final case class ContextSensitiveSlackPush(asUser: Option[SlackMessageRequest], asBot: SlackMessageRequest)
  object ContextSensitiveSlackPush {
    def insensitive(smr: SlackMessageRequest) = ContextSensitiveSlackPush(asUser = Some(smr), asBot = smr)
    def generate(fn: Boolean => SlackMessageRequest) = ContextSensitiveSlackPush(asUser = Some(fn(true)), asBot = fn(false))
  }

  def slackAuthorFromKifiUser(bu: BasicUser)(implicit imgConfig: S3ImageConfig): SlackAttachment.Author = SlackAttachment.Author(
    name = bu.fullName,
    link = Some(bu.path.absolute),
    icon = Some("https:" + bu.picturePath.getImageUrl.value.stripPrefix("https:"))
  )
}

class SlackPushGenerator @Inject() (
  db: Database,
  libRepo: LibraryRepo,
  clock: Clock,
  ktlRepo: KeepToLibraryRepo,
  keepRepo: KeepRepo,
  keepSourceAttributionRepo: KeepSourceAttributionRepo,
  basicUserRepo: BasicUserRepo,
  pathCommander: PathCommander,
  airbrake: AirbrakeNotifier,
  eliza: ElizaServiceClient,
  private implicit val imageConfig: S3ImageConfig,
  private implicit val executionContext: ExecutionContext)
    extends Logging {

  import SlackPushGenerator._

  def getPushItems(lts: LibraryToSlackChannel): Future[PushItems] = {
    // val (lib, changedKeeps) = db.readOnlyMaster { implicit s =>
    //   val lib = libRepo.get(lts.libraryId)
    //   val changedKeeps = keepRepo.getChangedKeepsFromLibrary(lts.libraryId, lts.lastProcessedKeepSeq getOrElse SequenceNumber.ZERO)
    //   (lib, changedKeeps)
    // }
    // val changedKeepIds = changedKeeps.map(_.id.get).toSet

    // val keepAndKtlByKeep = db.readOnlyMaster { implicit s =>
    //   val keepById = keepRepo.getActiveByIds(changedKeepIds)
    //   val ktlsByKeepId = ktlRepo.getAllByKeepIds(changedKeepIds).flatMapValues { ktls => ktls.find(_.libraryId == lts.libraryId) }
    //   changedKeepIds.flatAugmentWith(kId => for (k <- keepById.get(kId); ktl <- ktlsByKeepId.get(kId)) yield (k, ktl)).toMap
    // }

    // val keepsToPushFut = db.readOnlyReplicaAsync { implicit s =>
    //   (keepSourceAttributionRepo.getByKeepIds(changedKeepIds.toSet), lts.lastProcessedKeep.map(ktlRepo.get))
    // }.map {
    //   case (attributionByKeepId, lastPushedKtl) =>
    //     def shouldKeepBePushed(keep: Keep, ktl: KeepToLibrary): Boolean = {
    //       keep.source != KeepSource.Slack && (ktl.addedAt isAfter lts.changedStatusAt.minusSeconds(5))
    //     }

    //     def hasAlreadyBeenPushed(ktl: KeepToLibrary) = lastPushedKtl.exists { last =>
    //       ktl.addedAt.isBefore(last.addedAt) || (ktl.addedAt.isEqual(last.addedAt) && ktl.id.get.id <= last.id.get.id)
    //     }
    //     val (oldKeeps, newKeeps) = keepAndKtlByKeep.values.toSeq.partition { case (k, ktl) => hasAlreadyBeenPushed(ktl) }
    //     (oldKeeps, newKeeps.filter { case (k, ktl) => shouldKeepBePushed(k, ktl) }, attributionByKeepId)
    // }
    // val msgsToPushFut = eliza.getChangedMessagesFromKeeps(changedKeepIds, lts.lastProcessedMsgSeq getOrElse SequenceNumber.ZERO).map { changedMsgs =>
    //   def hasAlreadyBeenPushed(msg: CrossServiceMessage) = lts.lastProcessedMsg.exists(msg.id.id <= _.id)
    //   def shouldMessageBePushed(msg: CrossServiceMessage) = keepAndKtlByKeep.get(msg.keep).exists {
    //     case (k, ktl) =>
    //       def messageWasSentAfterKeepWasAddedToThisLibrary = msg.sentAt isAfter ktl.addedAt.minusSeconds(5)
    //       def messageWasSentAfterIntegrationWasActivated = msg.sentAt isAfter lts.changedStatusAt.minusSeconds(5)
    //       messageWasSentAfterKeepWasAddedToThisLibrary && messageWasSentAfterIntegrationWasActivated
    //   }
    //   val msgsWithKeep = changedMsgs.flatAugmentWith(msg => keepAndKtlByKeep.get(msg.keep).map(_._1)).map(_.swap)
    //   msgsWithKeep
    //     .filter { case (k, msg) => shouldMessageBePushed(msg) }
    //     .partition { case (k, msg) => hasAlreadyBeenPushed(msg) }
    // }

    // for {
    //   (oldKeeps, newKeeps, attributionByKeepId) <- keepsToPushFut
    //   (oldMsgs, newMsgs) <- msgsToPushFut
    //   users <- db.readOnlyReplicaAsync { implicit s =>
    //     val userIds = oldKeeps.flatMap(_._2.addedBy) ++ newKeeps.flatMap(_._2.addedBy) ++ oldMsgs.flatMap(_._2.sentBy.flatMap(_.left.toOption)) ++ newMsgs.flatMap(_._2.sentBy.flatMap(_.left.toOption))
    //     basicUserRepo.loadAll(userIds.toSet)
    //   }
    // } yield {
    //   PushItems(
    //     oldKeeps = oldKeeps.map { case (k, ktl) => KeepToPush(k, ktl) },
    //     newKeeps = newKeeps.map { case (k, ktl) => KeepToPush(k, ktl) },
    //     oldMsgs = oldMsgs.map { case (k, msg) => MessageToPush(k, msg) },
    //     newMsgs = newMsgs.map { case (k, msg) => MessageToPush(k, msg) },
    //     lib = lib,
    //     slackTeamId = lts.slackTeamId,
    //     slackChannelId = lts.slackChannelId,
    //     attribution = attributionByKeepId,
    //     users = users
    //   )
    // }
    Future { throw new Exception() }
  }

  // Figures out what the appropriate method is, based on the type of `item`, and generates the slack push for it
  def slackMessageForItem(item: PushItem, orgSettings: Option[OrganizationSettings])(implicit items: PushItems): Option[ContextSensitiveSlackPush] = {
    // import DescriptionElements._
    // item match {
    //   case PushItem.Digest(since) =>
    //     val libraryLink = LinkElement(pathCommander.libraryPageViaSlack(items.lib, items.slackTeamId).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, NotificationCategory.NonUser.LIBRARY_DIGEST)))
    //     Some(ContextSensitiveSlackPush(asUser = None, asBot = SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(DescriptionElements(
    //       items.lib.name, "has", (items.newKeeps.length, items.newMsgs.length) match {
    //         case (numKeeps, numMsgs) if numMsgs < 2 => DescriptionElements(numKeeps, "new keeps since", since, ".")
    //         case (numKeeps, numMsgs) if numKeeps < 2 => DescriptionElements(numMsgs, "new comments since", since, ".")
    //         case (numKeeps, numMsgs) => DescriptionElements(numKeeps, "new keeps and", numMsgs, "new comments since", since, ".")
    //       },
    //       "It's a bit too much to post here, but you can check it all out", "here" --> libraryLink
    //     )))))
    //   case PushItem.KeepToPush(k, ktl) =>
    //     Some(keepAsSlackMessage(k, items.lib, items.slackTeamId, items.attribution.get(k.id.get), ktl.addedBy.flatMap(items.users.get)))
    //   case PushItem.MessageToPush(k, msg) if msg.text.nonEmpty && orgSettings.exists(_.settingFor(StaticFeature.SlackCommentMirroring).safely.contains(StaticFeatureSetting.ENABLED)) =>
    //     Some(messageAsSlackMessage(msg, k, items.lib, items.slackTeamId, items.attribution.get(k.id.get), msg.sentBy.flatMap(_.left.toOption.flatMap(items.users.get))))
    //   case messageToSwallow: PushItem.MessageToPush =>
    //     None
    // }
    None
  }

  trait PushGenerator {
    def pushForKeep(keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution], user: Option[BasicUser])(implicit items: PushItems): ContextSensitiveSlackPush
    def pushForMessage(msg: CrossServiceMessage, keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution], user: Option[BasicUser])(implicit items: PushItems): ContextSensitiveSlackPush
  }
  def keepAsSlackMessage(keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution], user: Option[BasicUser])(implicit items: PushItems): ContextSensitiveSlackPush = {
    val generator = if (KifiSlackApp.experimentTeams.contains(items.slackTeamId)) ExperimentalGenerator else ProductionGenerator
    generator.pushForKeep(keep, lib, slackTeamId, attribution, user)
  }
  def messageAsSlackMessage(msg: CrossServiceMessage, keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution], user: Option[BasicUser])(implicit items: PushItems): ContextSensitiveSlackPush = {
    val generator = if (KifiSlackApp.experimentTeams.contains(items.slackTeamId)) ExperimentalGenerator else ProductionGenerator
    generator.pushForMessage(msg, keep, lib, slackTeamId, attribution, user)
  }

  /*
  V1 methods are for general use
   */
  object ProductionGenerator extends PushGenerator {
    override def pushForKeep(keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution], user: Option[BasicUser])(implicit items: PushItems): ContextSensitiveSlackPush = {
      import DescriptionElements._
      val category = NotificationCategory.NonUser.NEW_KEEP
      val userStr = user.fold[String]("Someone")(_.firstName)
      val keepElement = DescriptionElements(
        s"_${keep.title.getOrElse(keep.url).abbreviate(KEEP_TITLE_MAX_DISPLAY_LENGTH)}_",
        "  ",
        "View" --> LinkElement(pathCommander.keepPageOnUrlViaSlack(keep, slackTeamId).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, category, Some("viewArticle")))),
        "|",
        "Reply" --> LinkElement(pathCommander.keepPageOnKifiViaSlack(keep, slackTeamId).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, category, Some("reply"))))
      )

      // TODO(cam): once you backfill, `attribution` should be non-optional so you can simplify this match
      (keep.note, attribution) match {
        case (Some(note), _) => ContextSensitiveSlackPush.generate(asUser => SlackMessageRequest.fromKifi(
          text = DescriptionElements.formatForSlack(DescriptionElements(Some(s"*$userStr:*").filterNot(_ => asUser), Hashtags.format(note))),
          attachments = Seq(SlackAttachment.simple(DescriptionElements(SlackEmoji.newspaper, keepElement)))
        ))
        case (None, None) => ContextSensitiveSlackPush.generate(asUser => SlackMessageRequest.fromKifi(
          text = DescriptionElements.formatForSlack(Some(DescriptionElements(s"*$userStr*", "sent this")).filterNot(_ => asUser)),
          attachments = Seq(SlackAttachment.simple(DescriptionElements(SlackEmoji.newspaper, keepElement)))
        ))
        case (None, Some(attr)) => attr match {
          case ka: KifiAttribution => ContextSensitiveSlackPush.generate(asUser => SlackMessageRequest.fromKifi(
            text = DescriptionElements.formatForSlack(Some(DescriptionElements(s"*$userStr*", "sent this")).filterNot(_ => asUser)),
            attachments = Seq(SlackAttachment.simple(DescriptionElements(SlackEmoji.newspaper, keepElement)))
          ))
          case TwitterAttribution(tweet) => ContextSensitiveSlackPush.insensitive(SlackMessageRequest.fromKifi(
            text = DescriptionElements.formatForSlack(DescriptionElements(s"*${tweet.user.name}:*", Hashtags.format(tweet.text))),
            attachments = Seq(SlackAttachment.simple(DescriptionElements(SlackEmoji.newspaper, keepElement)))
          ))
          case SlackAttribution(msg, team) => ContextSensitiveSlackPush.insensitive(SlackMessageRequest.fromKifi(
            text = DescriptionElements.formatForSlack(DescriptionElements(s"*${msg.username.value}:*", msg.text)),
            attachments = Seq(SlackAttachment.simple(DescriptionElements(SlackEmoji.newspaper, keepElement)))
          ))
        }
      }
    }

    override def pushForMessage(msg: CrossServiceMessage, keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution], user: Option[BasicUser])(implicit items: PushItems): ContextSensitiveSlackPush = {
      airbrake.verify(msg.keep == keep.id.get, s"Message $msg does not belong to keep $keep")
      airbrake.verify(keep.recipients.libraries.contains(lib.id.get), s"Keep $keep is not in library $lib")
      import DescriptionElements._

      val category = NotificationCategory.NonUser.NEW_COMMENT
      def keepWebLink(subaction: String) = LinkElement(pathCommander.keepPageOnUrlViaSlack(keep, slackTeamId).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, category, Some(subaction))))
      def keepKifiLink(subaction: String) = LinkElement(pathCommander.keepPageOnKifiViaSlack(keep, slackTeamId).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, category, Some(subaction))))
      def msgLink(subaction: String) = LinkElement(pathCommander.keepPageOnMessageViaSlack(keep, slackTeamId, msg.id).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, category, Some(subaction))))

      val textAndLookHeres = CrossServiceMessage.splitOutLookHeres(msg.text)
      val slackAuthor = user.map(slackAuthorFromKifiUser).getOrElse {
        SlackAttachment.Author(name = "Someone", icon = Some(StaticImageUrls.KIFI_LOGO), link = None)
      }

      val msgText = DescriptionElements(textAndLookHeres.map {
        case Left(str) => DescriptionElements(Hashtags.format(str))
        case Right(Success((pointer, ref))) => pointer --> msgLink("lookHere")
        case Right(Failure(fail)) => "look here" --> msgLink("lookHere")
      })
      val lookHereAttachments = textAndLookHeres.collect {
        case Right(Success((pointer, ref))) =>
          imageUrlRegex.findFirstIn(ref) match {
            case Some(url) =>
              SlackAttachment.simple(DescriptionElements(SlackEmoji.magnifyingGlass, pointer --> msgLink("lookHereImage"))).withImageUrl(url)
            case None =>
              SlackAttachment.simple(DescriptionElements(
                SlackEmoji.magnifyingGlass, pointer --> msgLink("lookHere"), ": ",
                DescriptionElements.unlines(ref.lines.toSeq.map(ln => DescriptionElements(s"_${ln}_")))
              )).withFullMarkdown
          }
      }
      val keepAttachment = {
        val numComments = msg.commentIndexOnKeep.map(n => n + 1 + (if (keep.note.exists(_.nonEmpty)) 1 else 0)).filter(_ > 1)
        SlackAttachment.simple(DescriptionElements(
          SlackEmoji.newspaper,
          s"_${keep.title.getOrElse(keep.url).abbreviate(KEEP_TITLE_MAX_DISPLAY_LENGTH)}_",
          "  ",
          "View" --> keepWebLink("viewArticle"),
          "|",
          ("Reply" + numComments.map(n => s" ($n)").getOrElse("")) --> keepKifiLink("reply")
        ))
      }

      ContextSensitiveSlackPush.generate { asUser =>
        if (asUser) {
          SlackMessageRequest.fromKifi(
            text = DescriptionElements.formatForSlack(msgText),
            attachments = lookHereAttachments :+ keepAttachment
          )
        } else {
          SlackMessageRequest.fromKifi(
            text = "",
            attachments = SlackAttachment.simple(msgText).withAuthor(slackAuthor) +: lookHereAttachments :+ keepAttachment
          )
        }
      }
    }
  }

  /*
  V2 methods are used to test new formatting
   */
  object ExperimentalGenerator extends PushGenerator {
    override def pushForKeep(keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution], user: Option[BasicUser])(implicit items: PushItems): ContextSensitiveSlackPush = {
      import DescriptionElements._
      val category = NotificationCategory.NonUser.NEW_KEEP
      def keepWebLink(subaction: String) = pathCommander.keepPageOnUrlViaSlack(keep, slackTeamId).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, category, Some(subaction)))
      def keepKifiLink(subaction: String) = pathCommander.keepPageOnKifiViaSlack(keep, slackTeamId).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, category, Some(subaction)))
      val userStr = user.fold[String]("Someone")(_.firstName)

      val slackAuthor = user.map(slackAuthorFromKifiUser).getOrElse {
        SlackAttachment.Author(name = "Someone", icon = Some(StaticImageUrls.KIFI_LOGO), link = None)
      }
      val (keepMainAttachment, keepFooterAttachment) = {
        val title = keep.title.getOrElse(keep.url).abbreviate(KEEP_TITLE_MAX_DISPLAY_LENGTH)
        val Seq(viewLink, replyLink) = db.readWrite { implicit s =>
          Seq(keepWebLink("viewArticle"), keepKifiLink("reply")).map(link => LinkElement(pathCommander.shortened(pathCommander.shorten(link))))
        }
        val bigElement = DescriptionElements(s"_${title}_", "  ", "View" --> viewLink, "|", "Reply" --> replyLink)
        val tinyElement = DescriptionElements(title --> viewLink, " • ", "Reply" --> replyLink)
        val bigAttachment = SlackAttachment.simple(DescriptionElements(SlackEmoji.newspaper, bigElement))
        val tinyAttachment = SlackAttachment.footer(DescriptionElements(SlackEmoji.newspaper, tinyElement))
        (bigAttachment, tinyAttachment)
      }
      // Displaying a keep is non-trivial. Here's the big picture:
      // 1. If the keep has a note, display it as if it were a message (see messageAsSlackMessage below)
      // 2. If the keep has a foreign source (e.g., slack/twitter ingestion), make sure we send it as Kifi-bot with attribution
      // 3. Otherwise, fall back on sending the title and a few call-to-action links

      keep.note.map { note =>
        ContextSensitiveSlackPush(
          asUser = Some(SlackMessageRequest.fromKifi(
            text = DescriptionElements.formatForSlack(Hashtags.format(note)),
            attachments = Seq(keepFooterAttachment)
          )),
          asBot = SlackMessageRequest.fromKifi(
            text = "",
            attachments = Seq(SlackAttachment.simple(Hashtags.format(note)).withAuthor(slackAuthor), keepFooterAttachment)
          )
        )
      }.orElse {
        attribution.collect {
          case TwitterAttribution(tweet) => DescriptionElements(s"*${tweet.user.name}:*", Hashtags.format(tweet.text))
          case SlackAttribution(msg, team) => DescriptionElements(s"*${msg.username.value}:*", msg.text)
        }.map { text =>
          ContextSensitiveSlackPush(
            asUser = None,
            asBot = SlackMessageRequest.fromKifi(text = DescriptionElements.formatForSlack(text), attachments = Seq(keepMainAttachment))
          )
        }
      }.getOrElse {
        ContextSensitiveSlackPush.generate { asUser =>
          SlackMessageRequest.fromKifi(
            text = if (asUser) "" else s"*$userStr* sent this",
            attachments = Seq(keepMainAttachment)
          )
        }
      }
    }

    override def pushForMessage(msg: CrossServiceMessage, keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution], user: Option[BasicUser])(implicit items: PushItems): ContextSensitiveSlackPush = {
      airbrake.verify(msg.keep == keep.id.get, s"Message $msg does not belong to keep $keep")
      airbrake.verify(keep.recipients.libraries.contains(lib.id.get), s"Keep $keep is not in library $lib")
      import DescriptionElements._

      val category = NotificationCategory.NonUser.NEW_COMMENT
      def keepWebLink(subaction: String) = pathCommander.keepPageOnUrlViaSlack(keep, slackTeamId).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, category, Some(subaction)))
      def keepKifiLink(subaction: String) = pathCommander.keepPageOnKifiViaSlack(keep, slackTeamId).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, category, Some(subaction)))
      def msgLink(subaction: String) = pathCommander.keepPageOnMessageViaSlack(keep, slackTeamId, msg.id).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, category, Some(subaction)))

      val textAndLookHeres = CrossServiceMessage.splitOutLookHeres(msg.text)
      val slackAuthor = user.map(slackAuthorFromKifiUser).getOrElse {
        SlackAttachment.Author(name = "Someone", icon = Some(StaticImageUrls.KIFI_LOGO), link = None)
      }

      val msgText = DescriptionElements(textAndLookHeres.map {
        case Left(str) => DescriptionElements(Hashtags.format(str))
        case Right(Success((pointer, ref))) => pointer --> msgLink("lookHere")
        case Right(Failure(fail)) => "look here" --> msgLink("lookHere")
      })
      val lookHereAttachments = textAndLookHeres.collect {
        case Right(Success((pointer, ref))) =>
          imageUrlRegex.findFirstIn(ref) match {
            case Some(url) =>
              SlackAttachment.simple(DescriptionElements(SlackEmoji.magnifyingGlass, pointer --> msgLink("lookHereImage"))).withImageUrl(url)
            case None =>
              SlackAttachment.simple(DescriptionElements(
                SlackEmoji.magnifyingGlass, pointer --> msgLink("lookHere"), ": ",
                DescriptionElements.unlines(ref.lines.toSeq.map(ln => DescriptionElements(s"_${ln}_")))
              )).withFullMarkdown
          }
      }
      val keepAttachment = {
        val replyText = {
          val numComments = msg.commentIndexOnKeep.map(n => n + 1 + (if (keep.note.exists(_.nonEmpty)) 1 else 0)).filter(_ > 1)
          "Reply" + numComments.map(n => s" ($n)").getOrElse("")
        }
        val title = keep.title.getOrElse(keep.url).abbreviate(KEEP_TITLE_MAX_DISPLAY_LENGTH)
        val Seq(viewLink, replyLink) = db.readWrite { implicit s =>
          Seq(keepWebLink("viewArticle"), keepKifiLink("reply")).map(link => LinkElement(pathCommander.shortened(pathCommander.shorten(link))))
        }
        SlackAttachment.footer(DescriptionElements(
          SlackEmoji.newspaper, title --> viewLink, " • ", replyText --> replyLink
        ))
      }

      ContextSensitiveSlackPush.generate { asUser =>
        if (asUser) {
          SlackMessageRequest.fromKifi(
            text = DescriptionElements.formatForSlack(msgText),
            attachments = lookHereAttachments :+ keepAttachment
          )
        } else {
          SlackMessageRequest.fromKifi(
            text = "",
            attachments = SlackAttachment.simple(msgText).withAuthor(slackAuthor) +: lookHereAttachments :+ keepAttachment
          )
        }
      }
    }
  }
}

