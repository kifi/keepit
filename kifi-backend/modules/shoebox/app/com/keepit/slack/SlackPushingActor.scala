package com.keepit.slack

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.commanders.gen.BasicOrganizationGen
import com.keepit.common.akka.{ FortyTwoActor, SafeFuture }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core._
import com.keepit.common.db.Id.ord
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.strings._
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.discussion.{ CrossServiceMessage, Message }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model.LibrarySpace.OrganizationSpace
import com.keepit.model._
import com.keepit.slack.SlackPushingActor.PushItem.{ MessageToPush, KeepToPush }
import com.keepit.slack.models.SlackErrorCode._
import com.keepit.slack.models.SlackIntegration.{ BrokenSlackIntegration, ForbiddenSlackIntegration }
import com.keepit.slack.models._
import com.keepit.social.BasicUser
import com.kifi.juggle.ConcurrentTaskProcessingActor
import org.joda.time.{ DateTime, Duration }
import play.api.libs.json.Json

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

  val imageUrlRegex = """^https?://[^\s]*\.(png|jpg|jpeg|gif)""".r
  val jenUserId = ExternalId[User]("ae139ae4-49ad-4026-b215-1ece236f1322")

  sealed abstract class PushItem(val time: DateTime)
  object PushItem {
    case class Digest(since: DateTime) extends PushItem(since)
    case class KeepToPush(k: Keep, ktl: KeepToLibrary) extends PushItem(ktl.addedAt)
    case class MessageToPush(k: Keep, msg: CrossServiceMessage) extends PushItem(msg.sentAt)
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
      else items.sortBy(_.time)
    }
  }
}

class SlackPushingActor @Inject() (
  db: Database,
  basicOrganizationGen: BasicOrganizationGen,
  slackTeamRepo: SlackTeamRepo,
  libRepo: LibraryRepo,
  slackClient: SlackClientWrapper,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackChannelRepo: SlackChannelRepo,
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
  slackAnalytics: SlackAnalytics,
  val heimdalContextBuilder: HeimdalContextBuilderFactory,
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
    val (integrationsByIds, isAllowed, getIntegrationInfo) = db.readOnlyMaster { implicit session =>
      val integrationsByIds = integrationRepo.getByIds(integrationIds.toSet)

      val isAllowed = integrationsByIds.map {
        case (integrationId, integration) =>
          integrationId -> slackTeamMembershipRepo.getBySlackTeamAndUser(integration.slackTeamId, integration.slackUserId).exists { stm =>
            permissionCommander.getLibraryPermissions(integration.libraryId, stm.userId).contains(LibraryPermission.VIEW_LIBRARY)
          }
      }

      val getIntegrationInfo = {
        val slackTeamsById = slackTeamRepo.getBySlackTeamIds(integrationsByIds.values.map(_.slackTeamId).toSet)
        val settingsByOrgIds = orgConfigRepo.getByOrgIds(slackTeamsById.values.flatMap(_.organizationId).toSet).mapValues(_.settings)
        val slackChannelBySlackTeamAndChannelId = slackChannelRepo.getByChannelIds(integrationsByIds.values.map(sctl => (sctl.slackTeamId, sctl.slackChannelId)).toSet)
        integrationsByIds.map {
          case (integrationId, integration) =>
            val slackTeam = slackTeamsById(integration.slackTeamId)
            val slackChannel = slackChannelBySlackTeamAndChannelId((integration.slackTeamId, integration.slackChannelId))
            val settings = slackTeam.organizationId.flatMap(orgId => settingsByOrgIds.get(orgId))
            integrationId -> (slackChannel, settings)
        }
      }

      (integrationsByIds, isAllowed, getIntegrationInfo)
    }
    integrationsByIds.map {
      case (integrationId, integration) =>
        integrationId -> FutureHelpers.robustly(pushMaybe(integration, isAllowed, getIntegrationInfo)).map {
          case Success(_) =>
            ()
          case Failure(fail) =>
            slackLog.warn(s"Failed to push to $integrationId because ${fail.getMessage}")
            ()
        }
    }
  }

  private def pushMaybe(integration: LibraryToSlackChannel, isAllowed: Id[LibraryToSlackChannel] => Boolean, getIntegrationInfo: Id[LibraryToSlackChannel] => (SlackChannel, Option[OrganizationSettings])): Future[Unit] = {
    val (channel, settings) = getIntegrationInfo(integration.id.get)

    val futurePushMaybe = {
      if (isAllowed(integration.id.get)) doPush(integration, channel, settings)
      else Future.failed(ForbiddenSlackIntegration(integration))
    }

    futurePushMaybe andThen {
      case result =>
        val now = clock.now()
        val (nextPushAt, updatedStatus) = result match {
          case Success(_) =>
            (Some(now plus delayFromSuccessfulPush), None)
          case Failure(forbidden: ForbiddenSlackIntegration) =>
            slackLog.warn("Push Integration between", forbidden.integration.libraryId, "and", forbidden.integration.slackChannelId.value, "in team", forbidden.integration.slackTeamId.value, "is forbidden")
            (None, Some(SlackIntegrationStatus.Off))
          case Failure(broken: BrokenSlackIntegration) =>
            slackLog.warn("Push Integration between", broken.integration.libraryId, "and", broken.integration.slackChannelId.value, "in team", broken.integration.slackTeamId.value, "is broken")
            SafeFuture {
              val team = db.readOnlyReplica { implicit s =>
                slackTeamRepo.getBySlackTeamId(broken.integration.slackTeamId)
              }
              val org = db.readOnlyMaster { implicit s =>
                team.flatMap(_.organizationId).flatMap(basicOrganizationGen.getBasicOrganizationHelper)
              }
              val name = team.map(_.slackTeamName.value).getOrElse("???")
              val cause = broken.cause.map(_.toString).getOrElse("???")
              inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
                "Can't Push - Broken Slack integration of team", name, "and Kifi org", org, "channel", broken.integration.slackChannelId.value, "cause", cause)))
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

  private def doPush(integration: LibraryToSlackChannel, channel: SlackChannel, settings: Option[OrganizationSettings]): Future[Unit] = {
    getPushItems(integration).flatMap { implicit pushItems =>
      val botTokenOpt = db.readOnlyMaster { implicit s => slackTeamRepo.getBySlackTeamId(integration.slackTeamId).flatMap(_.kifiBot.map(_.token)) }
      for {
        _ <- pushNewItems(integration, channel, pushItems.sortedNewItems, settings)
        _ <- botTokenOpt.map(token => updatePushedKeeps(integration, pushItems.oldKeeps, token)).getOrElse(Future.successful(()))
        _ <- botTokenOpt.map(token => updatePushedMessages(integration, pushItems.oldMsgs, token)).getOrElse(Future.successful(()))
      } yield ()
    }
  }

  private def pushNewItems(integration: LibraryToSlackChannel, channel: SlackChannel, sortedItems: Seq[PushItem], settings: Option[OrganizationSettings])(implicit pushItems: PushItems): Future[Unit] = {
    FutureHelpers.sequentialExec(sortedItems) { item =>
      slackMessageForItem(item, settings).fold(Future.successful(())) { itemMsg =>
        slackClient.sendToSlackHoweverPossible(integration.slackTeamId, integration.slackChannelId, itemMsg).recoverWith {
          case SlackFail.NoValidPushMethod => Future.failed(BrokenSlackIntegration(integration, None, Some(SlackFail.NoValidPushMethod)))
        }.map { pushedMessageOpt =>
          db.readWrite { implicit s =>
            item match {
              case PushItem.Digest(_) =>
                pushItems.newKeeps.map(_.ktl.id.get).maxOpt.foreach { ktlId => integrationRepo.updateLastProcessedKeep(integration.id.get, ktlId) }
                pushItems.newMsgs.map(_.msg.id).maxOpt.foreach { msgId => integrationRepo.updateLastProcessedMsg(integration.id.get, msgId) }
              case PushItem.KeepToPush(k, ktl) =>
                log.info(s"[SLACK-PUSH-ACTOR] for integration ${integration.id.get}, keep ${k.id.get} had message ${pushedMessageOpt.map(_.timestamp)}")
                pushedMessageOpt.foreach { response =>
                  slackPushForKeepRepo.intern(SlackPushForKeep.fromMessage(integration, k.id.get, itemMsg, response))
                }
                integrationRepo.updateLastProcessedKeep(integration.id.get, ktl.id.get)
              case PushItem.MessageToPush(k, kifiMsg) =>
                pushedMessageOpt.foreach { response =>
                  slackPushForMessageRepo.intern(SlackPushForMessage.fromMessage(integration, kifiMsg.id, itemMsg, response))
                }
                integrationRepo.updateLastProcessedMsg(integration.id.get, kifiMsg.id)
            }
          }
        } tap { msgFut =>
          msgFut.onComplete {
            case Failure(_) =>
            case Success(_) =>
              implicit val contextBuilder = heimdalContextBuilder()
              val category = item match {
                case _: PushItem.Digest => NotificationCategory.NonUser.LIBRARY_DIGEST
                case PushItem.KeepToPush(k, ktl) =>
                  contextBuilder += ("keepId", k.id.get.id)
                  contextBuilder += ("libraryId", ktl.libraryId.id)
                  NotificationCategory.NonUser.NEW_KEEP
                case PushItem.MessageToPush(k, _) =>
                  contextBuilder += ("threadId", k.id.get.id)
                  NotificationCategory.NonUser.NEW_COMMENT
              }
              slackAnalytics.trackNotificationSent(integration.slackTeamId, integration.slackChannelId, channel.slackChannelName, category, contextBuilder.build)
              ()
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

  private def updatePushedKeeps(integration: LibraryToSlackChannel, oldKeeps: Seq[PushItem.KeepToPush], botToken: SlackBotAccessToken)(implicit pushItems: PushItems): Future[Unit] = {
    val slackPushesByKeep = db.readOnlyMaster { implicit s =>
      slackPushForKeepRepo.getEditableByIntegrationAndKeepIds(integration.id.get, oldKeeps.map(_.k.id.get).toSet)
    }
    FutureHelpers.sequentialExec(oldKeeps.flatAugmentWith { case KeepToPush(k, _) => slackPushesByKeep.get(k.id.get) }) {
      case (KeepToPush(k, ktl), oldPush) =>
        val updatedMessage = keepAsSlackMessage(k, pushItems.lib, pushItems.slackTeamId, pushItems.attribution.get(k.id.get), k.userId.flatMap(pushItems.users.get))
        if (oldPush.messageRequest.safely.contains(updatedMessage)) Future.successful(())
        else {
          slackClient.updateMessage(botToken, integration.slackChannelId, oldPush.timestamp, SlackMessageUpdateRequest.fromMessageRequest(updatedMessage)).map { response =>
            db.readWrite { implicit s =>
              slackPushForKeepRepo.save(oldPush.withMessageRequest(updatedMessage).withTimestamp(response.timestamp))
            }
            ()
          }.recover {
            case SlackErrorCode(EDIT_WINDOW_CLOSED) | SlackErrorCode(CANT_UPDATE_MESSAGE) | SlackErrorCode(CHANNEL_NOT_FOUND) | SlackErrorCode(MESSAGE_NOT_FOUND) =>
              slackLog.warn(s"Failed to update keep ${k.id.get} because slack says it's uneditable, removing it from the cache")
              db.readWrite { implicit s => slackPushForKeepRepo.save(oldPush.uneditable) }
              ()
            case fail: SlackAPIErrorResponse =>
              slackLog.warn(s"Failed to update keep ${k.id.get} from integration ${integration.id.get} because ${fail.getMessage}")
              ()
          }
        }
    }
  }

  private def updatePushedMessages(integration: LibraryToSlackChannel, oldMsgs: Seq[PushItem.MessageToPush], botToken: SlackBotAccessToken)(implicit pushItems: PushItems): Future[Unit] = {
    val slackPushesByMsg = db.readOnlyMaster { implicit s =>
      slackPushForMessageRepo.getEditableByIntegrationAndKeepIds(integration.id.get, oldMsgs.map(_.msg.id).toSet)
    }
    FutureHelpers.sequentialExec(oldMsgs.flatAugmentWith { case MessageToPush(_, msg) => slackPushesByMsg.get(msg.id) }) {
      case (MessageToPush(k, msg), oldPush) =>
        val updatedMessage = messageAsSlackMessage(msg, k, pushItems.lib, pushItems.slackTeamId, pushItems.attribution.get(k.id.get), k.userId.flatMap(pushItems.users.get))
        if (oldPush.messageRequest.safely.contains(updatedMessage)) Future.successful(())
        else {
          slackClient.updateMessage(botToken, integration.slackChannelId, oldPush.timestamp, SlackMessageUpdateRequest.fromMessageRequest(updatedMessage)).map { response =>
            db.readWrite { implicit s =>
              slackPushForMessageRepo.save(oldPush.withMessageRequest(updatedMessage).withTimestamp(response.timestamp))
            }
            ()
          }.recover {
            case SlackErrorCode(EDIT_WINDOW_CLOSED) | SlackErrorCode(CANT_UPDATE_MESSAGE) | SlackErrorCode(CHANNEL_NOT_FOUND) | SlackErrorCode(MESSAGE_NOT_FOUND) =>
              slackLog.warn(s"Failed to update message ${msg.id} because slack says it's uneditable, removing it from the cache")
              db.readWrite { implicit s => slackPushForMessageRepo.save(oldPush.uneditable) }
              ()
            case fail: SlackAPIErrorResponse =>
              slackLog.warn(s"Failed to update message ${msg.id} from integration ${integration.id.get} because ${fail.getMessage}")
              ()
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
    val changedKeepIds = changedKeeps.map(_.id.get).toSet

    val keepAndKtlByKeep = db.readOnlyMaster { implicit s =>
      val keepById = keepRepo.getActiveByIds(changedKeepIds)
      val ktlsByKeepId = ktlRepo.getAllByKeepIds(changedKeepIds).flatMapValues { ktls => ktls.find(_.libraryId == lts.libraryId) }
      changedKeepIds.flatAugmentWith(kId => for (k <- keepById.get(kId); ktl <- ktlsByKeepId.get(kId)) yield (k, ktl)).toMap
    }

    val keepsToPushFut = db.readOnlyReplicaAsync { implicit s =>
      (keepSourceAttributionRepo.getByKeepIds(changedKeepIds.toSet), lts.lastProcessedKeep.map(ktlRepo.get))
    }.map {
      case (attributionByKeepId, lastPushedKtl) =>
        def shouldKeepBePushed(keep: Keep, ktl: KeepToLibrary): Boolean = {
          // TODO(ryan): temporarily making this more aggressive, something bad is happening in prod
          keep.source != KeepSource.slack && ktl.addedAt.isAfter(lts.changedStatusAt)
        }

        def hasAlreadyBeenPushed(ktl: KeepToLibrary) = lastPushedKtl.exists { last =>
          ktl.addedAt.isBefore(last.addedAt) || (ktl.addedAt.isEqual(last.addedAt) && ktl.id.get.id <= last.id.get.id)
        }
        val (oldKeeps, newKeeps) = keepAndKtlByKeep.values.toSeq.partition { case (k, ktl) => hasAlreadyBeenPushed(ktl) }
        (oldKeeps, newKeeps.filter { case (k, ktl) => shouldKeepBePushed(k, ktl) }, attributionByKeepId)
    }
    val msgsToPushFut = eliza.getChangedMessagesFromKeeps(changedKeepIds, lts.lastProcessedMsgSeq getOrElse SequenceNumber.ZERO).map { changedMsgs =>
      def hasAlreadyBeenPushed(msg: CrossServiceMessage) = lts.lastProcessedMsg.exists(msg.id.id <= _.id)
      def shouldMessageBePushed(msg: CrossServiceMessage) = keepAndKtlByKeep.get(msg.keep).exists {
        case (k, ktl) =>
          def messageWasSentAfterKeepWasAddedToThisLibrary = ktl.addedAt isBefore msg.sentAt
          def keepWasAddedToThisLibraryAfterIntegrationWasActivated = ktl.addedAt isAfter lts.changedStatusAt
          messageWasSentAfterKeepWasAddedToThisLibrary && keepWasAddedToThisLibraryAfterIntegrationWasActivated
      }
      val msgsWithKeep = changedMsgs.flatAugmentWith(msg => keepAndKtlByKeep.get(msg.keep).map(_._1)).map(_.swap)
      msgsWithKeep
        .filter { case (k, msg) => shouldMessageBePushed(msg) }
        .partition { case (k, msg) => hasAlreadyBeenPushed(msg) }
    }

    for {
      (oldKeeps, newKeeps, attributionByKeepId) <- keepsToPushFut
      (oldMsgs, newMsgs) <- msgsToPushFut
      users <- db.readOnlyReplicaAsync { implicit s =>
        val userIds = oldKeeps.flatMap(_._2.addedBy) ++ newKeeps.flatMap(_._2.addedBy) ++ oldMsgs.flatMap(_._2.sentBy.flatMap(_.left.toOption)) ++ newMsgs.flatMap(_._2.sentBy.flatMap(_.left.toOption))
        basicUserRepo.loadAll(userIds.toSet)
      }
    } yield {
      PushItems(
        oldKeeps = oldKeeps.map { case (k, ktl) => KeepToPush(k, ktl) },
        newKeeps = newKeeps.map { case (k, ktl) => KeepToPush(k, ktl) },
        oldMsgs = oldMsgs.map { case (k, msg) => MessageToPush(k, msg) },
        newMsgs = newMsgs.map { case (k, msg) => MessageToPush(k, msg) },
        lib = lib,
        slackTeamId = lts.slackTeamId,
        slackChannelId = lts.slackChannelId,
        attribution = attributionByKeepId,
        users = users
      )
    }
  }

  private def slackMessageForItem(item: PushItem, orgSettings: Option[OrganizationSettings])(implicit items: PushItems): Option[SlackMessageRequest] = {
    import DescriptionElements._
    val libraryLink = LinkElement(pathCommander.libraryPageViaSlack(items.lib, items.slackTeamId).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, NotificationCategory.NonUser.LIBRARY_DIGEST)))
    item match {
      case PushItem.Digest(since) => Some(SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(DescriptionElements(
        items.lib.name, "has", (items.newKeeps.length, items.newMsgs.length) match {
          case (numKeeps, numMsgs) if numMsgs < 2 => DescriptionElements(numKeeps, "new keeps since", since, ".")
          case (numKeeps, numMsgs) if numKeeps < 2 => DescriptionElements(numMsgs, "new comments since", since, ".")
          case (numKeeps, numMsgs) => DescriptionElements(numKeeps, "new keeps and", numMsgs, "new comments since", since, ".")
        },
        "It's a bit too much to post here, but you can check it all out", "here" --> libraryLink
      ))))
      case PushItem.KeepToPush(k, ktl) =>
        Some(keepAsSlackMessage(k, items.lib, items.slackTeamId, items.attribution.get(k.id.get), ktl.addedBy.flatMap(items.users.get)))
      case PushItem.MessageToPush(k, msg) if msg.text.nonEmpty && orgSettings.exists(_.settingFor(StaticFeature.SlackCommentMirroring).safely.contains(StaticFeatureSetting.ENABLED)) =>
        Some(messageAsSlackMessage(msg, k, items.lib, items.slackTeamId, items.attribution.get(k.id.get), msg.sentBy.flatMap(_.left.toOption.flatMap(items.users.get))))
      case messageToSwallow: PushItem.MessageToPush =>
        None
    }
  }
  private def keepAsSlackMessage(keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution], user: Option[BasicUser])(implicit items: PushItems): SlackMessageRequest = {
    import DescriptionElements._
    val category = NotificationCategory.NonUser.NEW_KEEP
    val userStr = user.fold[String]("Someone")(_.firstName)
    val userColor = user.map(u => if (u.externalId == jenUserId) LibraryColor.PURPLE else LibraryColor.byHash(Seq(keep.externalId.id, u.externalId.id)))
    val keepElement = DescriptionElements(
      s"_${keep.title.getOrElse(keep.url).abbreviate(KEEP_TITLE_MAX_DISPLAY_LENGTH)}_",
      "  ",
      "View" --> LinkElement(pathCommander.keepPageOnUrlViaSlack(keep, slackTeamId).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, category, Some("viewArticle")))),
      "|",
      "Reply" --> LinkElement(pathCommander.keepPageOnKifiViaSlack(keep, slackTeamId).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, category, Some("reply"))))
    )

    // TODO(cam): once you backfill, `attribution` should be non-optional so you can simplify this match
    if (slackTeamId == KifiSlackApp.BrewstercorpTeamId || slackTeamId == KifiSlackApp.KifiSlackTeamId) {
      (keep.note, attribution) match {
        case (Some(note), _) => SlackMessageRequest.fromKifi(
          text = DescriptionElements.formatForSlack(DescriptionElements(s"*$userStr:*", Hashtags.format(note))),
          attachments = Seq(SlackAttachment.simple(keepElement))
        )
        case (None, None) => SlackMessageRequest.fromKifi(
          text = DescriptionElements.formatForSlack(DescriptionElements(s"*$userStr*", "sent", keepElement))
        )
        case (None, Some(attr)) => attr match {
          case ka: KifiAttribution => SlackMessageRequest.fromKifi(
            text = DescriptionElements.formatForSlack(DescriptionElements(s"*${ka.keptBy.firstName}*", "sent", keepElement))
          )
          case TwitterAttribution(tweet) => SlackMessageRequest.fromKifi(
            text = DescriptionElements.formatForSlack(DescriptionElements(s"*${tweet.user.name}:*", Hashtags.format(tweet.text))),
            attachments = Seq(SlackAttachment.simple(keepElement))
          )
          case SlackAttribution(msg, team) => SlackMessageRequest.fromKifi(
            text = DescriptionElements.formatForSlack(DescriptionElements(s"*${msg.username.value}:*", msg.text)),
            attachments = Seq(SlackAttachment.simple(keepElement))
          )
        }
      }
    } else (keep.note, attribution) match {
      case (Some(note), _) => SlackMessageRequest.fromKifi(
        text = DescriptionElements.formatForSlack(keepElement),
        attachments = Seq(SlackAttachment.simple(DescriptionElements(s"*$userStr:*", Hashtags.format(note))).withColorMaybe(userColor.map(_.hex)).withFullMarkdown)
      )
      case (None, None) => SlackMessageRequest.fromKifi(
        text = DescriptionElements.formatForSlack(DescriptionElements(s"*$userStr*", "sent", keepElement))
      )
      case (None, Some(attr)) => attr match {
        case ka: KifiAttribution => SlackMessageRequest.fromKifi(
          text = DescriptionElements.formatForSlack(DescriptionElements(s"*${ka.keptBy.firstName}*", "sent", keepElement))
        )
        case TwitterAttribution(tweet) => SlackMessageRequest.fromKifi(
          text = DescriptionElements.formatForSlack(keepElement),
          attachments = Seq(SlackAttachment.simple(DescriptionElements(s"*${tweet.user.name}:*", Hashtags.format(tweet.text))))
        )
        case SlackAttribution(msg, team) => SlackMessageRequest.fromKifi(
          text = DescriptionElements.formatForSlack(keepElement),
          attachments = Seq(SlackAttachment.simple(DescriptionElements(s"*${msg.username.value}:*", msg.text)))
        )
      }
    }
  }
  private def messageAsSlackMessage(msg: CrossServiceMessage, keep: Keep, lib: Library, slackTeamId: SlackTeamId, attribution: Option[SourceAttribution], user: Option[BasicUser])(implicit items: PushItems): SlackMessageRequest = {
    airbrake.verify(msg.keep == keep.id.get, s"Message $msg does not belong to keep $keep")
    airbrake.verify(keep.recipients.libraries.contains(lib.id.get), s"Keep $keep is not in library $lib")
    import DescriptionElements._

    val userStr = user.fold[String]("Someone")(_.firstName)
    val userColor = user.map(u => if (u.externalId == jenUserId) LibraryColor.PURPLE else LibraryColor.byHash(Seq(keep.externalId.id, u.externalId.id)))

    val category = NotificationCategory.NonUser.NEW_COMMENT
    def keepLink(subaction: String) = LinkElement(pathCommander.keepPageOnUrlViaSlack(keep, slackTeamId).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, category, Some(subaction))))
    def msgLink(subaction: String) = LinkElement(pathCommander.keepPageOnMessageViaSlack(keep, slackTeamId, msg.id).withQuery(SlackAnalytics.generateTrackingParams(items.slackChannelId, category, Some(subaction))))

    val keepElement = {
      DescriptionElements(
        s"_${keep.title.getOrElse(keep.url).abbreviate(KEEP_TITLE_MAX_DISPLAY_LENGTH)}_",
        "  ",
        "View" --> keepLink("viewArticle"),
        "|",
        "Reply" --> keepLink("reply")
      )
    }

    val textAndLookHeres = CrossServiceMessage.splitOutLookHeres(msg.text)

    if (slackTeamId == KifiSlackApp.BrewstercorpTeamId || slackTeamId == KifiSlackApp.KifiSlackTeamId) {
      SlackMessageRequest.fromKifi(
        text = if (msg.isDeleted) "[comment has been deleted]"
        else DescriptionElements.formatForSlack(DescriptionElements(s"*$userStr:*", textAndLookHeres.map {
          case Left(str) => DescriptionElements(str)
          case Right(Success((pointer, ref))) => pointer --> msgLink("lookHere")
          case Right(Failure(fail)) => "look here" --> msgLink("lookHere")
        })),
        attachments = textAndLookHeres.collect {
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
        } :+ SlackAttachment.simple(keepElement)
      )
    } else SlackMessageRequest.fromKifi(
      text = DescriptionElements.formatForSlack(keepElement),
      attachments =
        if (msg.isDeleted) Seq(SlackAttachment.simple("[comment has been deleted]"))
        else SlackAttachment.simple(DescriptionElements(s"*$userStr:*", textAndLookHeres.map {
          case Left(str) => DescriptionElements(str)
          case Right(Success((pointer, ref))) => pointer --> msgLink("lookHere")
          case Right(Failure(fail)) => "look here" --> msgLink("lookHere")
        })).withFullMarkdown.withColorMaybe(userColor.map(_.hex)) +: textAndLookHeres.collect {
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
    )
  }
}

