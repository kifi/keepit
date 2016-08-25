package com.keepit.slack

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.akka.FortyTwoActor
import play.api.http.Status
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.slack.SlackPushGenerator.PushItem.{ KeepToPush, MessageToPush }
import com.keepit.slack.SlackPushGenerator.{ PushItem, PushItems }
import com.keepit.slack.models.SlackErrorCode._
import com.keepit.slack.models.SlackIntegration.{ BrokenSlackIntegration, ForbiddenSlackIntegration }
import com.keepit.slack.models._
import com.kifi.juggle.ConcurrentTaskProcessingActor
import org.joda.time.Duration

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object SlackPushingActor {
  val pushTimeout = Duration.standardMinutes(10) // we will wait this long for a process to complete before we assume it is incompetent
  val minPushConcurrency = 5
  val maxPushConcurrency = 15

  val delayFromSuccessfulPush = Duration.standardMinutes(30)
  val delayFromFailedPush = Duration.standardMinutes(5)
}

class SlackPushingActor @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackClient: SlackClientWrapper,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackChannelRepo: SlackChannelRepo,
  integrationRepo: LibraryToSlackChannelRepo,
  liteLibrarySlackInfoCache: LiteLibrarySlackInfoCache,
  permissionCommander: PermissionCommander,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  orgConfigRepo: OrganizationConfigurationRepo,
  slackPushForKeepRepo: SlackPushForKeepRepo,
  slackPushForMessageRepo: SlackPushForMessageRepo,
  slackAnalytics: SlackAnalytics,
  generator: SlackPushGenerator,
  val heimdalContextBuilder: HeimdalContextBuilderFactory,
  implicit val executionContext: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends FortyTwoActor(airbrake) with ConcurrentTaskProcessingActor[Id[LibraryToSlackChannel]] {

  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SLACK)

  import SlackPushingActor._

  protected val minConcurrentTasks = minPushConcurrency
  protected val maxConcurrentTasks = maxPushConcurrency

  protected def pullTasks(limit: Int): Future[Seq[Id[LibraryToSlackChannel]]] = {
    // db.readWrite { implicit session =>
    //   val integrationIds = integrationRepo.getRipeForPushing(limit, pushTimeout)
    //   Future.successful(integrationIds.filter(integrationRepo.markAsPushing(_, pushTimeout)))
    // }
    Future { Seq.empty }
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
            (None, Some(SlackIntegrationStatus.Broken))
          case Failure(error) =>
            slackLog.warn(s"Failed to push to Slack via integration ${integration.id.get}:" + error.getMessage)
            (Some(now plus delayFromFailedPush), None)
        }

        db.readWrite { implicit session =>
          integrationRepo.updateAfterPush(integration.id.get, nextPushAt, updatedStatus getOrElse integration.status)
          updatedStatus.foreach(_ => liteLibrarySlackInfoCache.remove(LiteLibrarySlackInfoKey(integration.libraryId)))
        }
    }
  }

  private def doPush(integration: LibraryToSlackChannel, channel: SlackChannel, settings: Option[OrganizationSettings]): Future[Unit] = {
    // generator.getPushItems(integration).flatMap { implicit pushItems =>
    //   for {
    //     _ <- pushNewItems(integration, channel, pushItems.sortedNewItems, settings)
    //     _ <- updatePushedKeeps(integration, pushItems.oldKeeps)
    //     _ <- updatePushedMessages(integration, pushItems.oldMsgs)
    //   } yield ()
    // }
    Future {}
  }

  private def pushNewItems(integration: LibraryToSlackChannel, channel: SlackChannel, sortedItems: Seq[PushItem], settings: Option[OrganizationSettings])(implicit pushItems: PushItems): Future[Unit] = {
    FutureHelpers.sequentialExec(sortedItems) { item =>
      generator.slackMessageForItem(item, settings).fold(Future.successful(())) { itemMsg =>
        val push = {
          val userPush = for {
            user <- item.userAttribution
            userMsg <- itemMsg.asUser
          } yield slackClient.sendToSlackAsUser(user, integration.slackTeamId, integration.slackChannelId, userMsg).map(resp => Option((resp, userMsg)))

          userPush.getOrElse(Future.failed(SlackFail.NoValidToken)).recoverWith {
            case SlackFail.NoValidToken | SlackErrorCode(_) =>
              slackClient.sendToSlackHoweverPossible(integration.slackTeamId, integration.slackChannelId, itemMsg.asBot).map(_.map(resp => (resp, itemMsg.asBot))).recoverWith {
                case SlackAPIErrorResponse(Status.REQUEST_ENTITY_TOO_LARGE, _, _)
                  | SlackAPIErrorResponse(Status.REQUEST_URI_TOO_LONG, _, _) => Future.successful(None) // pretend we succeeded
                case SlackFail.NoValidPushMethod => Future.failed(BrokenSlackIntegration(integration, None, Some(SlackFail.NoValidPushMethod)))
              }
          }
        }
        push.map { pushedMessageOpt =>
          db.readWrite { implicit s =>
            item match {
              case PushItem.Digest(_) =>
                pushItems.newKeeps.map(_.ktl.id.get).maxOpt.foreach { ktlId => integrationRepo.updateLastProcessedKeep(integration.id.get, ktlId) }
                pushItems.newMsgs.map(_.msg.id).maxOpt.foreach { msgId => integrationRepo.updateLastProcessedMsg(integration.id.get, msgId) }
              case PushItem.KeepToPush(k, ktl) =>
                log.info(s"[SLACK-PUSH-ACTOR] for integration ${integration.id.get}, keep ${k.id.get} had message ${pushedMessageOpt.map(_._1._2.timestamp)}")
                pushedMessageOpt.foreach {
                  case ((slackUserId, response), request) =>
                    slackPushForKeepRepo.intern(SlackPushForKeep.fromMessage(integration, k.id.get, slackUserId = slackUserId, request, response))
                }
                integrationRepo.updateLastProcessedKeep(integration.id.get, ktl.id.get)
              case PushItem.MessageToPush(k, kifiMsg) =>
                pushedMessageOpt.foreach {
                  case ((slackUserId, response), request) =>
                    slackPushForMessageRepo.intern(SlackPushForMessage.fromMessage(integration, kifiMsg.id, slackUserId = slackUserId, request, response))
                }
                integrationRepo.updateLastProcessedMsg(integration.id.get, kifiMsg.id)
            }
          }
        } tap { msgFut =>
          msgFut.onComplete {
            case Failure(fail) =>
              airbrake.notify(fail)
              slackLog.error("Could not push", item match {
                case _: PushItem.Digest => "a digest"
                case PushItem.KeepToPush(k, ktl) => s"keep ${k.id.get.id}"
                case PushItem.MessageToPush(k, msg) => s"msg ${msg.id.id}"
              }, "because", fail.getMessage)
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
              slackAnalytics.trackNotificationSent(integration.slackTeamId, integration.slackChannelId, Some(channel.slackChannelName), category, contextBuilder.build)
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

  private def updatePushedKeeps(integration: LibraryToSlackChannel, oldKeeps: Seq[PushItem.KeepToPush])(implicit pushItems: PushItems): Future[Unit] = {
    val (slackPushesByKeep, tokensBySlackUser, kifiBot) = db.readOnlyMaster { implicit s =>
      val slackPushes = slackPushForKeepRepo.getEditableByIntegrationAndKeepIds(integration.id.get, oldKeeps.map(_.k.id.get).toSet)
      val (tokensByUser, kifiBot) = getTokensThatCanPush(integration.slackTeamId, slackPushes.values.map(_.slackUserId).toSet)
      (slackPushes, tokensByUser, kifiBot)
    }
    FutureHelpers.sequentialExec(oldKeeps.flatAugmentWith { case KeepToPush(k, _) => slackPushesByKeep.get(k.id.get) }) {
      case (KeepToPush(k, ktl), oldPush) =>
        val update = for {
          (updatedMessage, pushToken) <- {
            def updated = generator.keepAsSlackMessage(k, pushItems.lib, pushItems.slackTeamId, pushItems.attribution.get(k.id.get), k.userId.flatMap(pushItems.users.get))
            kifiBot.collect {
              case KifiSlackBot(botUserId, botToken) if oldPush.slackUserId == botUserId => (updated.asBot, botToken)
            }.orElse(for {
              token <- tokensBySlackUser.get(oldPush.slackUserId)
              updatedPush <- updated.asUser
            } yield (updatedPush, token))
          }
          _ <- Some(()) if !oldPush.messageRequest.safely.contains(updatedMessage)
        } yield {
          slackClient.updateMessage(pushToken, integration.slackChannelId, oldPush.timestamp, SlackMessageUpdateRequest.fromMessageRequest(updatedMessage)).map { response =>
            db.readWrite { implicit s =>
              slackPushForKeepRepo.save(oldPush.withMessageRequest(updatedMessage).withTimestamp(response.timestamp))
            }
            ()
          }.recover {
            case SlackErrorCode(EDIT_WINDOW_CLOSED) | SlackErrorCode(CANT_UPDATE_MESSAGE) | SlackErrorCode(CHANNEL_NOT_FOUND) | SlackErrorCode(MESSAGE_NOT_FOUND) | SlackErrorCode(ACCOUNT_INACTIVE) =>
              slackLog.warn(s"Failed to update keep ${k.id.get} because slack says it's uneditable, removing it from the cache")
              db.readWrite { implicit s => slackPushForKeepRepo.save(oldPush.uneditable) }
              ()
            case fail: SlackAPIErrorResponse =>
              slackLog.warn(s"Failed to update keep ${k.id.get} from integration ${integration.id.get} because ${fail.getMessage}")
              ()
          }
        }
        update getOrElse Future.successful(())
    }
  }

  private def updatePushedMessages(integration: LibraryToSlackChannel, oldMsgs: Seq[PushItem.MessageToPush])(implicit pushItems: PushItems): Future[Unit] = {
    val (slackPushesByMsg, tokensBySlackUser, kifiBot) = db.readOnlyMaster { implicit s =>
      val slackPushes = slackPushForMessageRepo.getEditableByIntegrationAndKeepIds(integration.id.get, oldMsgs.map(_.msg.id).toSet)
      val (tokensByUser, kifiBot) = getTokensThatCanPush(integration.slackTeamId, slackPushes.values.map(_.slackUserId).toSet)
      (slackPushes, tokensByUser, kifiBot)
    }
    FutureHelpers.sequentialExec(oldMsgs.flatAugmentWith { case MessageToPush(_, msg) => slackPushesByMsg.get(msg.id) }) {
      case (MessageToPush(k, msg), oldPush) =>
        val update = for {
          (updatedMessage, pushToken) <- {
            def updated = generator.messageAsSlackMessage(msg, k, pushItems.lib, pushItems.slackTeamId, pushItems.attribution.get(k.id.get), k.userId.flatMap(pushItems.users.get))
            kifiBot.collect {
              case KifiSlackBot(botUserId, botToken) if oldPush.slackUserId == botUserId => (updated.asBot, botToken)
            }.orElse(for {
              token <- tokensBySlackUser.get(oldPush.slackUserId)
              updatedPush <- updated.asUser
            } yield (updatedPush, token))
          }
          _ <- Some(()) if !oldPush.messageRequest.safely.contains(updatedMessage)
        } yield {
          slackClient.updateMessage(pushToken, integration.slackChannelId, oldPush.timestamp, SlackMessageUpdateRequest.fromMessageRequest(updatedMessage)).map { response =>
            db.readWrite { implicit s =>
              slackPushForMessageRepo.save(oldPush.withMessageRequest(updatedMessage).withTimestamp(response.timestamp))
            }
            ()
          }.recover {
            case SlackErrorCode(EDIT_WINDOW_CLOSED)
              | SlackErrorCode(CANT_UPDATE_MESSAGE)
              | SlackErrorCode(CHANNEL_NOT_FOUND)
              | SlackErrorCode(MESSAGE_NOT_FOUND)
              | SlackErrorCode(ACCOUNT_INACTIVE) =>
              slackLog.warn(s"Failed to update message ${msg.id} because slack says it's uneditable, removing it from the cache")
              db.readWrite { implicit s => slackPushForMessageRepo.save(oldPush.uneditable) }
              ()
            case fail: SlackAPIErrorResponse =>
              slackLog.warn(s"Failed to update message ${msg.id} from integration ${integration.id.get} because ${fail.getMessage}")
              ()
          }
        }
        update getOrElse Future.successful(())
    }
  }

  private def getTokensThatCanPush(slackTeamId: SlackTeamId, slackUserIds: Set[SlackUserId])(implicit session: RSession): (Map[SlackUserId, SlackAccessToken], Option[KifiSlackBot]) = {
    val kifiBot = slackTeamRepo.getBySlackTeamId(slackTeamId).flatMap(_.kifiBot)
    val tokensByUser = {
      val slackMemberships = slackTeamMembershipRepo.getBySlackIdentities(slackUserIds.map(slackTeamId -> _))
      slackMemberships.flatMap {
        case ((_, slackUserId), stm) => stm.getTokenIncludingScopes(Set(SlackAuthScope.ChatWriteUser)).map(token => slackUserId -> token)
      }
    }
    (tokensByUser, kifiBot)
  }
}

