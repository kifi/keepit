package com.keepit.slack

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.akka.{ FortyTwoActor, SafeFuture }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core._
import com.keepit.common.db.Id.ord
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.strings._
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.discussion.{ CrossServiceMessage, Message }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.LibrarySpace.OrganizationSpace
import com.keepit.model._
import com.keepit.slack.models.SlackErrorCode._
import com.keepit.slack.models.SlackIntegration.{ BrokenSlackIntegration, ForbiddenSlackIntegration }
import com.keepit.slack.models._
import com.keepit.social.BasicUser
import com.kifi.juggle.ConcurrentTaskProcessingActor
import org.joda.time.{ DateTime, Duration }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object SlackPushingActor {
  val pushTimeout = Duration.standardMinutes(10) // we will wait this long for a process to complete before we assume it is incompetent
  val minPushConcurrency = 5
  val maxPushConcurrency = 15

  val delayFromSuccessfulPush = Duration.standardMinutes(30)
  val delayFromFailedPush = Duration.standardMinutes(5)
  val MAX_ITEMS_TO_PUSH = 7
  val KEEP_TITLE_MAX_DISPLAY_LENGTH = 60

  val imageUrlRegex = """^https?://.*?\.(png|jpg|jpeg|gif|gifv)""".r

  sealed abstract class PushItem(val time: DateTime)
  object PushItem {
    case class Digest(since: DateTime) extends PushItem(since)
    case class KeepToPush(k: Keep, ktl: KeepToLibrary) extends PushItem(ktl.addedAt)
    case class MessageToPush(k: Keep, msg: CrossServiceMessage) extends PushItem(msg.sentAt)
  }
  case class PushItems(
      oldKeeps: Seq[(Keep, KeepToLibrary)],
      newKeeps: Seq[(Keep, KeepToLibrary)],
      oldMsgs: Map[Keep, Seq[CrossServiceMessage]],
      newMsgs: Map[Keep, Seq[CrossServiceMessage]],
      lib: Library,
      slackTeamId: SlackTeamId,
      attribution: Map[Id[Keep], SourceAttribution],
      users: Map[Id[User], BasicUser]) {
    def maxKeepSeq: Option[SequenceNumber[Keep]] = Seq(oldKeeps, newKeeps).flatMap(_.map(_._1.seq)).maxOpt
    def maxMsgSeq: Option[SequenceNumber[Message]] = Seq(oldMsgs.values, newMsgs.values).flatMap(_.flatMap(_.map(_.seq))).maxOpt
    def sortedNewItems: Seq[PushItem] = {
      val items: Seq[PushItem] = newKeeps.map { case (k, ktl) => PushItem.KeepToPush(k, ktl) } ++ newMsgs.flatMap { case (k, msgs) => msgs.map(msg => PushItem.MessageToPush(k, msg)) }
      if (items.length > MAX_ITEMS_TO_PUSH) Seq(PushItem.Digest(newKeeps.map(_._2.addedAt).minOpt getOrElse newMsgs.values.flatten.map(_.sentAt).min))
      else items.sortBy(_.time)
    }
  }
}

class SlackPushingActor @Inject() (
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
  slackPushForKeepRepo: SlackPushForKeepRepo,
  slackPushForMessageRepo: SlackPushForMessageRepo,
  orgExperimentRepo: OrganizationExperimentRepo,
  eliza: ElizaServiceClient,
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
    log.info(s"[SLACK-PUSH-ACTOR] Processing $integrationIds")
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
        integrationId -> FutureHelpers.robustly(pushMaybe(integration, isAllowed, getSettings)).map {
          case Success(_) => ()
          case Failure(fail) =>
            slackLog.warn(s"Failed to push to $integrationId because ${fail.getMessage}")
            ()
        }
    }
  }

  private def pushMaybe(integration: LibraryToSlackChannel, isAllowed: Id[LibraryToSlackChannel] => Boolean, getSettings: Id[LibraryToSlackChannel] => Option[OrganizationSettings]): Future[Unit] = {
    val futurePushMaybe = {
      if (isAllowed(integration.id.get)) doPush(integration, getSettings(integration.id.get))
      else Future.failed(ForbiddenSlackIntegration(integration))
    }

    futurePushMaybe andThen {
      case result =>
        val now = clock.now()
        val (nextPushAt, updatedStatus) = result match {
          case Success(_) =>
            (Some(now plus delayFromSuccessfulPush), None)
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
            slackLog.warn(s"Failed to push to Slack via integration ${integration.id.get}:" + error.getMessage)
            (Some(now plus delayFromFailedPush), None)
        }

        db.readWrite { implicit session =>
          integrationRepo.updateAfterPush(integration.id.get, nextPushAt, updatedStatus getOrElse integration.status)
        }
    }
  }

  private def doPush(integration: LibraryToSlackChannel, settings: Option[OrganizationSettings]): Future[Unit] = {
    getPushItems(integration).flatMap { implicit pushItems =>
      // First, do a best effort to update all the "old" shit, which can only happen with a slack bot user
      // We do not keep track of whether this succeeds, we just assume that it does
      db.readOnlyMaster { implicit s => slackTeamRepo.getBySlackTeamId(integration.slackTeamId).flatMap(_.kifiBot.map(_.token)) }.foreach { botToken =>
        log.info(s"[SLACK-PUSH-ACTOR] While pushing to ${integration.id.get}, found bot token $botToken")
        for {
          _ <- FutureHelpers.sequentialExec(pushItems.oldKeeps) {
            case (k, ktl) =>
              // lookup k in a cache to find a slack message timestamp
              slackPushForKeepRepo.getTimestampFromCache(integration.id.get, k.id.get).map { oldKeepTimestamp =>
                // regenerate the slack message
                val updatedKeepMessage = keepAsSlackMessage(k, pushItems.lib, pushItems.slackTeamId, pushItems.attribution.get(k.id.get), k.userId.flatMap(pushItems.users.get))
                // call the SlackClient to try and update the message
                log.info(s"[SLACK-PUSH-ACTOR] While pushing to ${integration.id.get}, found timestamp $oldKeepTimestamp for keep ${k.id.get}, trying to update")
                slackClient.updateMessage(botToken, integration.slackChannelId, oldKeepTimestamp, updatedKeepMessage).andThen {
                  case Success(msgResponse) => log.info(s"[SLACK-PUSH-ACTOR] Updated keep ${k.id.get}!")
                  case Failure(ex) => log.error(s"[SLACK-PUSH-ACTOR] Failed to update keep ${k.id.get} because ${ex.getMessage}.")
                }.imap(_ => ()).recover {
                  case SlackErrorCode(EDIT_WINDOW_CLOSED) | SlackErrorCode(CANT_UPDATE_MESSAGE) =>
                    slackLog.warn(s"Failed to update keep ${k.id.get} because slack says it's uneditable, removing it from the cache")
                    slackPushForKeepRepo.dropTimestampFromCache(integration.id.get, k.id.get)
                  case fail: SlackAPIErrorResponse =>
                    slackLog.warn(s"Failed to update keep ${k.id.get} from integration ${integration.id.get} with timestamp $oldKeepTimestamp because ${fail.getMessage}")
                    ()
                }
              }.getOrElse(Future.successful(()))
          }
          _ <- FutureHelpers.sequentialExec(pushItems.oldMsgs) {
            case (k, msgs) =>
              FutureHelpers.sequentialExec(msgs) { msg =>
                slackPushForMessageRepo.getTimestampFromCache(integration.id.get, msg.id).fold(Future.successful(())) { oldCommentTimestamp =>
                  // regenerate the slack message if there is some text to push
                  val updatedCommentMessage = messageAsSlackMessage(msg, k, pushItems.lib, pushItems.slackTeamId, pushItems.attribution.get(k.id.get), k.userId.flatMap(pushItems.users.get))
                  // call the SlackClient to try and update the message
                  slackClient.updateMessage(botToken, integration.slackChannelId, oldCommentTimestamp, updatedCommentMessage).imap(_ => ()).recover {
                    case SlackErrorCode(CANT_UPDATE_MESSAGE) | SlackErrorCode(EDIT_WINDOW_CLOSED) =>
                      slackLog.warn(s"Failed to update comment ${msg.id} because Slack says it's uneditable, removing it from the cache")
                      slackPushForMessageRepo.dropTimestampFromCache(integration.id.get, msg.id)
                    case fail: SlackAPIErrorResponse =>
                      slackLog.warn(s"Failed to update comment ${msg.id} from integration ${integration.id.get} with timestamp $oldCommentTimestamp because ${fail.getMessage}")
                      ()
                  }
                }
              }
          }
        } yield ()
      }

      // Now push new things, updating the integration state as we go
      FutureHelpers.sequentialExec(pushItems.sortedNewItems) { item =>
        slackMessageForItem(item, settings).fold(Future.successful(Option.empty[SlackMessageResponse])) { itemMsg =>
          slackClient.sendToSlackHoweverPossible(integration.slackTeamId, integration.slackChannelId, itemMsg).recoverWith {
            case SlackFail.NoValidPushMethod => Future.failed(BrokenSlackIntegration(integration, None, Some(SlackFail.NoValidPushMethod)))
          }
        }.map { pushedMessageOpt =>
          db.readWrite { implicit s =>
            item match {
              case PushItem.Digest(_) =>
                pushItems.newKeeps.map(_._2.id.get).maxOpt.foreach { ktlId => integrationRepo.updateLastProcessedKeep(integration.id.get, ktlId) }
                pushItems.newMsgs.values.flatten.map(_.id).maxOpt.foreach { msgId => integrationRepo.updateLastProcessedMsg(integration.id.get, msgId) }
              case PushItem.KeepToPush(k, ktl) =>
                log.info(s"[SLACK-PUSH-ACTOR] for integration ${integration.id.get}, keep ${k.id.get} had message ${pushedMessageOpt.map(_.timestamp)}")
                pushedMessageOpt.foreach { pushedMessage =>
                  slackPushForKeepRepo.intern(SlackPushForKeep.fromMessage(integration, k.id.get, pushedMessage))
                }
                integrationRepo.updateLastProcessedKeep(integration.id.get, ktl.id.get)
              case PushItem.MessageToPush(k, kifiMsg) =>
                pushedMessageOpt.foreach { pushedMessage =>
                  slackPushForMessageRepo.intern(SlackPushForMessage.fromMessage(integration, kifiMsg.id, pushedMessage))
                }
                integrationRepo.updateLastProcessedMsg(integration.id.get, kifiMsg.id)
            }
          }
        }
      }.andThen {
        case Success(_) =>
          db.readWrite { implicit s =>
            integrationRepo.updateLastProcessedSeqs(integration.id.get, pushItems.maxKeepSeq, pushItems.maxMsgSeq)
          }
      }
    }
  }

  private def getPushItems(lts: LibraryToSlackChannel): Future[PushItems] = {
    val (lib, changedKeeps) = db.readOnlyMaster { implicit s =>
      val lib = libRepo.get(lts.libraryId)
      val changedKeeps = keepRepo.getChangedKeepsFromLibrary(lts.libraryId, lts.lastProcessedKeepSeq getOrElse SequenceNumber.ZERO)
      (lib, changedKeeps)
    }
    if (changedKeeps.nonEmpty) log.info(s"[SLACK-PUSH-ACTOR] Integration ${lts.id.get} has ${changedKeeps.length} changed keeps with seq >= ${lts.lastProcessedKeepSeq}")
    val changedKeepIds = changedKeeps.map(_.id.get).toSet

    val keepsById = db.readOnlyMaster { implicit s => keepRepo.getByIds(changedKeepIds) }

    val keepsToPushFut = db.readOnlyReplicaAsync { implicit s =>
      val ktlsByKeepId = ktlRepo.getAllByKeepIds(changedKeepIds).flatMap { case (kId, ktls) => ktls.find(_.libraryId == lts.libraryId).map(kId -> _) }

      val attributionByKeepId = keepSourceAttributionRepo.getByKeepIds(changedKeepIds.toSet)
      def comesFromDestinationChannel(keepId: Id[Keep]): Boolean = attributionByKeepId.get(keepId).exists {
        case sa: SlackAttribution => lts.slackChannelId == sa.message.channel.id
        case _ => false
      }

      val lastPushedKtl = lts.lastProcessedKeep.map(ktlRepo.get)
      def hasAlreadyBeenPushed(ktl: KeepToLibrary) = lastPushedKtl.exists { last =>
        ktl.addedAt.isBefore(last.addedAt) || (ktl.addedAt.isEqual(last.addedAt) && ktl.id.get.id <= last.id.get.id)
      }
      val (oldKeeps, newKeeps) = changedKeeps.flatMap { k =>
        for { ktl <- ktlsByKeepId.get(k.id.get) } yield (k, ktl)
      }.partition { case (k, ktl) => hasAlreadyBeenPushed(ktl) }
      (oldKeeps, newKeeps.filter { case (k, ktl) => !comesFromDestinationChannel(k.id.get) }, attributionByKeepId)
    }
    val msgsToPushFut = eliza.getChangedMessagesFromKeeps(changedKeepIds, lts.lastProcessedMsgSeq getOrElse SequenceNumber.ZERO).map { changedMsgs =>
      def hasAlreadyBeenPushed(msg: CrossServiceMessage) = lts.lastProcessedMsg.exists(lastId => msg.id.id <= lastId.id) // Any idea why the implicit Ordering[Id[Message]] won't help me here?
      val (oldMsgsUngrouped, newMsgsUngrouped) = changedMsgs.partition(hasAlreadyBeenPushed)
      val oldMsgs = oldMsgsUngrouped.groupBy(_.keep).flatMap { case (kId, msgs) => keepsById.get(kId).map(_ -> msgs) }
      val newMsgs = newMsgsUngrouped.groupBy(_.keep).flatMap { case (kId, msgs) => keepsById.get(kId).map(_ -> msgs) }
      (oldMsgs, newMsgs)
    }

    for {
      (oldKeeps, newKeeps, attributionByKeepId) <- keepsToPushFut
      (oldMsgs, newMsgs) <- msgsToPushFut
      users <- db.readOnlyReplicaAsync { implicit s =>
        val userIds = oldKeeps.flatMap(_._2.addedBy) ++ newKeeps.flatMap(_._2.addedBy) ++ oldMsgs.flatMap(_._2.flatMap(_.sentBy)) ++ newMsgs.flatMap(_._2.flatMap(_.sentBy))
        basicUserRepo.loadAll(userIds.toSet)
      }
    } yield {
      PushItems(
        oldKeeps = oldKeeps,
        newKeeps = newKeeps,
        oldMsgs = oldMsgs,
        newMsgs = newMsgs,
        lib = lib,
        slackTeamId = lts.slackTeamId,
        attribution = attributionByKeepId,
        users = users
      )
    }
  }

  private def slackMessageForItem(item: PushItem, orgSettings: Option[OrganizationSettings])(implicit items: PushItems): Option[SlackMessageRequest] = {
    import DescriptionElements._
    item match {
      case PushItem.Digest(since) => Some(SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(DescriptionElements(
        items.lib.name, "has", (items.newKeeps.length, items.newMsgs.values.map(_.length).sum) match {
          case (numKeeps, numMsgs) if numMsgs < 2 => DescriptionElements(numKeeps, "new keeps since", since, ".")
          case (numKeeps, numMsgs) if numKeeps < 2 => DescriptionElements(numMsgs, "new comments since", since, ".")
          case (numKeeps, numMsgs) => DescriptionElements(numKeeps, "new keeps and", numMsgs, "new comments since", since, ".")
        },
        "It's a bit too much to post here, but you can check it all out", "here" --> LinkElement(pathCommander.libraryPageViaSlack(items.lib, items.slackTeamId))
      ))))
      case PushItem.KeepToPush(k, ktl) =>
        Some(keepAsSlackMessage(k, items.lib, items.slackTeamId, items.attribution.get(k.id.get), ktl.addedBy.flatMap(items.users.get)))
      case PushItem.MessageToPush(k, msg) if msg.text.nonEmpty && orgSettings.exists(_.settingFor(StaticFeature.SlackCommentMirroring).safely.contains(StaticFeatureSetting.ENABLED)) =>
        Some(messageAsSlackMessage(msg, k, items.lib, items.slackTeamId, items.attribution.get(k.id.get), msg.sentBy.flatMap(items.users.get)))
      case messageToSwallow: PushItem.MessageToPush =>
        None
    }
  }
  private def keepAsSlackMessage(keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution], user: Option[BasicUser]): SlackMessageRequest = {
    import DescriptionElements._

    val userStr = user.fold[String]("Someone")(_.firstName)
    val keepElement = {
      DescriptionElements(
        "“_", keep.title.getOrElse(keep.url).abbreviate(KEEP_TITLE_MAX_DISPLAY_LENGTH), "_”",
        "  ",
        "View Article" --> LinkElement(pathCommander.keepPageOnUrlViaSlack(keep, slackTeamId)),
        "•",
        "Reply to Thread" --> LinkElement(pathCommander.keepPageOnKifiViaSlack(keep, slackTeamId))
      )
    }

    (keep.note, attribution) match {
      case (None, None) =>
        SlackMessageRequest.fromKifi(text = DescriptionElements.formatForSlack(DescriptionElements(s"*$userStr*", "sent", keepElement)))
      case (Some(note), _) =>
        SlackMessageRequest.fromKifi(
          text = DescriptionElements.formatForSlack(keepElement),
          attachments = Seq(SlackAttachment.simple(DescriptionElements(s"*$userStr:*", Hashtags.format(note))).withFullMarkdown)
        )
      case (None, Some(attr)) =>
        SlackMessageRequest.fromKifi(text = DescriptionElements.formatForSlack(keepElement),
          attachments = Seq(SlackAttachment.simple(attr match {
            case TwitterAttribution(tweet) => DescriptionElements(s"*${tweet.user.name}:*", Hashtags.format(tweet.text))
            case SlackAttribution(msg, team) => DescriptionElements(s"*${msg.username}:*", msg.text)
          })))
    }
  }
  private def messageAsSlackMessage(msg: CrossServiceMessage, keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution], user: Option[BasicUser]): SlackMessageRequest = {
    airbrake.verify(msg.keep == keep.id.get, s"Message $msg does not belong to keep $keep")
    airbrake.verify(keep.connections.libraries.contains(lib.id.get), s"Keep $keep is not in library $lib")
    import DescriptionElements._

    val userStr = user.fold[String]("Someone")(_.firstName)
    val keepLink = LinkElement(pathCommander.keepPageOnUrlViaSlack(keep, slackTeamId))
    val keepElement = {
      DescriptionElements(
        "_", keep.title.getOrElse(keep.url).abbreviate(KEEP_TITLE_MAX_DISPLAY_LENGTH), "_",
        "  ",
        "View Article" --> keepLink,
        "•",
        "Reply to Thread" --> LinkElement(pathCommander.keepPageOnKifiViaSlack(keep, slackTeamId))
      )
    }

    val textAndLookHeres = CrossServiceMessage.splitOutLookHeres(msg.text)
    SlackMessageRequest.fromKifi(
      text = DescriptionElements.formatForSlack(keepElement),
      attachments =
        if (msg.isDeleted) Seq(SlackAttachment.simple("[comment has been deleted]"))
        else SlackAttachment.simple(DescriptionElements(s"*$userStr:*", textAndLookHeres.map {
          case Left(str) => DescriptionElements(str)
          case Right(Success((pointer, ref))) => pointer --> keepLink
          case Right(Failure(fail)) => "look here" --> keepLink
        })).withFullMarkdown.withColor(LibraryColor.BLUE.hex) +: textAndLookHeres.collect {
          case Right(Success((pointer, ref))) =>
            imageUrlRegex.findFirstIn(ref) match {
              case Some(url) =>
                SlackAttachment.simple(DescriptionElements(SlackEmoji.magnifyingGlass, pointer --> keepLink)).withImageUrl(url)
              case None =>
                SlackAttachment.simple(DescriptionElements(
                  SlackEmoji.magnifyingGlass, pointer --> keepLink, ": ",
                  DescriptionElements.unlines(ref.lines.toSeq.map(ln => DescriptionElements("_", ln, "_")))
                ))
            }
        }
    )
  }
}

