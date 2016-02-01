package com.keepit.slack

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.{ PermissionCommander, RawBookmarkRepresentation, KeepInterner }
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.time.Clock
import com.keepit.common.util.UrlClassifier
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.OrganizationSpace
import com.kifi.juggle._
import com.keepit.model._
import com.keepit.slack.models.SlackIntegration.{ ForbiddenSlackIntegration, BrokenSlackIntegration }
import com.keepit.slack.models._
import org.joda.time.Period
import com.keepit.common.time._

import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object SlackIngestionConfig {
  val nextIngestionDelayAfterFailure = Period.minutes(10)
  val nextIngestionDelayWithoutNewMessages = Period.minutes(2)
  val nextIngestionDelayAfterNewMessages = Period.seconds(30)
  val maxIngestionDelayAfterCommand = Period.seconds(15)

  val ingestionTimeout = Period.minutes(30)
  val minChannelIngestionConcurrency = 15
  val maxChannelIngestionConcurrency = 30
  val messageBatchSize = 50

  val slackLinkPattern = """<(.*?)(?:\|(.*?))?>""".r
}

class SlackIngestingActor @Inject() (
  db: Database,
  integrationRepo: SlackChannelToLibraryRepo,
  slackChannelRepo: SlackChannelRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  permissionCommander: PermissionCommander,
  libraryRepo: LibraryRepo,
  slackClient: SlackClientWrapper,
  urlClassifier: UrlClassifier,
  keepInterner: KeepInterner,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  slackOnboarder: SlackOnboarder,
  orgConfigRepo: OrganizationConfigurationRepo,
  implicit val ec: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends FortyTwoActor(airbrake) with ConcurrentTaskProcessingActor[Id[SlackChannelToLibrary]] {

  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SLACK)
  import SlackIngestionConfig._

  protected val minConcurrentTasks = minChannelIngestionConcurrency
  protected val maxConcurrentTasks = maxChannelIngestionConcurrency

  protected def pullTasks(limit: Int): Future[Seq[Id[SlackChannelToLibrary]]] = {
    db.readWrite { implicit session =>
      val integrationIds = integrationRepo.getRipeForIngestion(limit, ingestionTimeout)
      log.info(s"[SLACK-INGEST] Found ${integrationIds.length}/$limit integrations to process next.")
      integrationRepo.markAsIngesting(integrationIds: _*)
      Future.successful(integrationIds)
    }
  }

  protected def processTasks(integrationIds: Seq[Id[SlackChannelToLibrary]]): Map[Id[SlackChannelToLibrary], Future[Unit]] = {
    val (integrationsByIds, isAllowed, getTokenWithScopes, getSettings) = db.readOnlyMaster { implicit session =>
      val integrationsByIds = integrationRepo.getByIds(integrationIds.toSet)

      val isAllowed = integrationsByIds.map {
        case (integrationId, integration) =>
          integrationId -> slackTeamMembershipRepo.getBySlackTeamAndUser(integration.slackTeamId, integration.slackUserId).exists { stm =>
            permissionCommander.getLibraryPermissions(integration.libraryId, stm.userId).contains(LibraryPermission.ADD_KEEPS)
          }
      }

      val getTokenWithScopes = {
        val slackMemberships = slackTeamMembershipRepo.getBySlackUserIds(integrationsByIds.values.map(_.slackUserId).toSet)
        integrationsByIds.map {
          case (integrationId, integration) =>
            integrationId -> slackMemberships.get(integration.slackUserId).flatMap(_.tokenWithScopes)
        }
      }

      val getSettings = {
        val orgIdsByIntegrationIds = integrationsByIds.mapValues(_.space).collect { case (integrationId, OrganizationSpace(orgId)) => integrationId -> orgId }
        val settingsByOrgIds = orgConfigRepo.getByOrgIds(orgIdsByIntegrationIds.values.toSet).mapValues(_.settings)
        orgIdsByIntegrationIds.mapValues(settingsByOrgIds.apply).get _
      }

      (integrationsByIds, isAllowed, getTokenWithScopes, getSettings)
    }
    integrationsByIds.map {
      case (integrationId, integration) =>
        integrationId -> ingestMaybe(integration, isAllowed, getTokenWithScopes, getSettings).imap(_ => ())
    }
  }

  private def ingestMaybe(integration: SlackChannelToLibrary, isAllowed: Id[SlackChannelToLibrary] => Boolean, getTokenWithScopes: Id[SlackChannelToLibrary] => Option[SlackTokenWithScopes], getSettings: Id[SlackChannelToLibrary] => Option[OrganizationSettings]): Future[Option[SlackTimestamp]] = {
    val futureIngestionMaybe = {
      if (isAllowed(integration.id.get)) {
        getTokenWithScopes(integration.id.get) match {
          case Some(tokenWithScopes) if SlackAuthScope.ingest subsetOf tokenWithScopes.scopes => doIngest(tokenWithScopes, getSettings(integration.id.get), integration)
          case invalidTokenOpt => Future.failed(BrokenSlackIntegration(integration, invalidTokenOpt.map(_.token), None))
        }
      } else {
        Future.failed(ForbiddenSlackIntegration(integration))
      }
    }

    futureIngestionMaybe andThen {
      case result =>
        val now = clock.now()
        val (nextIngestionAt, updatedStatus) = result match {
          case Success(lastMsgTimestamp) =>
            if (integration.lastIngestedAt.isEmpty) { // this is the first time we've tried ingesting for this integration
              slackOnboarder.talkAboutIntegration(integration)
            }
            val delay = lastMsgTimestamp match {
              case Some(newTimestamp) if !integration.lastMessageTimestamp.contains(newTimestamp) => nextIngestionDelayAfterNewMessages
              case _ => nextIngestionDelayWithoutNewMessages
            }
            (Some(now plus delay), None)
          case Failure(forbidden: ForbiddenSlackIntegration) =>
            slackLog.warn("Integration between", forbidden.integration.libraryId, "and", forbidden.integration.slackChannelName.value, "in team", forbidden.integration.slackTeamId.value, "is forbidden")
            (None, Some(SlackIntegrationStatus.Off))
          case Failure(broken: BrokenSlackIntegration) =>
            slackLog.warn("Integration between", broken.integration.libraryId, "and", broken.integration.slackChannelName.value, "in team", broken.integration.slackTeamId.value, "is broken")
            (None, Some(SlackIntegrationStatus.Broken))
          case Failure(error) =>
            //airbrake.notify(s"Failed to ingest from Slack via integration ${integration.id.get}", error) // please fix do this doesn't send so aggressively
            log.warn(s"[SLACK-INGEST] Failed to ingest from Slack via integration ${integration.id.get}:" + error.getMessage)
            (Some(now plus nextIngestionDelayAfterFailure), None)
        }
        db.readWrite { implicit session =>
          integrationRepo.updateAfterIngestion(integration.id.get, nextIngestionAt, updatedStatus getOrElse integration.status)
        }
    }
  }

  private def doIngest(tokenWithScopes: SlackTokenWithScopes, settings: Option[OrganizationSettings], integration: SlackChannelToLibrary): Future[Option[SlackTimestamp]] = {
    val shouldAddReactions = settings.exists(_.settingFor(Feature.SlackIngestionReaction).contains(FeatureSetting.ENABLED))
    FutureHelpers.foldLeftUntil(Stream.continually(()))(integration.lastMessageTimestamp) {
      case (lastMessageTimestamp, ()) =>
        getLatestMessagesWithLinks(tokenWithScopes.token, integration.slackChannelName, lastMessageTimestamp, Some(messageBatchSize)).flatMap { messages =>
          val (newLastMessageTimestamp, ingestedMessages) = ingestMessages(integration, messages)
          val futureReactions = if (shouldAddReactions) {
            FutureHelpers.sequentialExec(ingestedMessages.toSeq.sortBy(_.timestamp)) { message =>
              slackClient.addReaction(tokenWithScopes.token, SlackReaction.robotFace, message.channel.id, message.timestamp) recover {
                case SlackAPIFailure(_, SlackAPIFailure.Error.alreadyReacted, _) => ()
              }
            }
          } else Future.successful(Unit)
          futureReactions imap { _ =>
            (newLastMessageTimestamp orElse lastMessageTimestamp, newLastMessageTimestamp.isEmpty)
          }
        } recoverWith {
          case failure @ SlackAPIFailure(_, SlackAPIFailure.Error.invalidAuth, _) => Future.failed(BrokenSlackIntegration(integration, Some(tokenWithScopes.token), Some(failure)))
        }
    }
  }

  private def ingestMessages(integration: SlackChannelToLibrary, messages: Seq[SlackMessage]): (Option[SlackTimestamp], Set[SlackMessage]) = {
    log.info(s"[SLACK-INGEST] Ingesting links from ${messages.length} messages from ${integration.slackChannelName.value}")
    val slackUsers = messages.map(_.userId).toSet
    val userBySlackId = db.readOnlyMaster { implicit s =>
      slackTeamMembershipRepo.getBySlackUserIds(slackUsers).flatMap { case (slackUser, stm) => stm.userId.map(slackUser -> _) }
    }
    // The following block sucks, it should all happen within the same session but that KeepInterner doesn't allow it
    val (integrationOwner, library) = db.readOnlyMaster { implicit session =>
      val userId = slackTeamMembershipRepo.getBySlackTeamAndUser(integration.slackTeamId, integration.slackUserId).flatMap(_.userId).getOrElse {
        val message = s"Could not find a valid SlackMembership for ${(integration.slackTeamId, integration.slackUserId)} for stl ${integration.id.get}"
        airbrake.notify(message)
        throw new IllegalStateException(message)
      }
      val library = libraryRepo.get(integration.libraryId)
      (userId, library)
    }
    val rawBookmarksByUser = messages.groupBy(msg => userBySlackId.getOrElse(msg.userId, integrationOwner)).map {
      case (user, msgs) => user -> msgs.flatMap(toRawBookmarks).distinctBy(_.url)
    }
    log.info(s"[SLACK-INGEST] Extracted these urls from those messages: ${rawBookmarksByUser.values.flatten.map(_.url).toSet}")
    val ingestedMessages = rawBookmarksByUser.flatMap {
      case (user, rawBookmarks) =>
        val (_, failed) = keepInterner.internRawBookmarks(rawBookmarks, user, library, KeepSource.slack)(HeimdalContext.empty)
        (rawBookmarks.toSet -- failed).flatMap(_.sourceAttribution.collect { case SlackAttribution(message) => message })
    }.toSet
    messages.headOption.foreach { msg =>
      db.readWrite { implicit s => slackChannelRepo.getOrCreate(integration.slackTeamId, msg.channel.id, msg.channel.name) }
    }
    val lastMessageTimestamp = messages.map(_.timestamp).maxOpt
    lastMessageTimestamp.foreach { timestamp =>
      db.readWrite { implicit session =>
        integrationRepo.updateLastMessageTimestamp(integration.id.get, timestamp)
      }
    }
    (lastMessageTimestamp, ingestedMessages)
  }

  private def doNotIngest(message: SlackMessage): Boolean = message.userId.value.trim.isEmpty || SlackUsername.doNotIngest.contains(message.username)
  private def doNotIngest(url: String): Boolean = urlClassifier.isSocialActivity(url) || urlClassifier.isSlackFile(url)

  private def toRawBookmarks(message: SlackMessage): Set[RawBookmarkRepresentation] = {
    if (doNotIngest(message)) Set.empty[RawBookmarkRepresentation]
    else {
      val linksFromText = slackLinkPattern.findAllMatchIn(message.text).toList.flatMap { m =>
        m.subgroups.map(Option(_).map(_.trim).filter(_.nonEmpty)) match {
          case List(Some(url), titleOpt) => Some(url -> titleOpt)
          case _ => None
        }
      }.toMap

      val linksFromAttachments = message.attachments.flatMap { attachment =>
        (attachment.title.flatMap(_.link) orElse attachment.fromUrl).map { url =>
          url -> attachment
        }
      }.toMap

      (linksFromText.keySet ++ linksFromAttachments.keySet).collect {
        case url if !doNotIngest(url) =>
          val title = linksFromText.get(url).flatten orElse linksFromAttachments.get(url).flatMap(_.title.map(_.value))
          RawBookmarkRepresentation(
            title = title,
            url = url,
            canonical = None,
            openGraph = None,
            keptAt = Some(message.timestamp.toDateTime),
            sourceAttribution = Some(SlackAttribution(message)),
            note = None
          )
      }
    }
  }

  private def getLatestMessagesWithLinks(token: SlackAccessToken, channelName: SlackChannelName, lastMessageTimestamp: Option[SlackTimestamp], limit: Option[Int]): Future[Seq[SlackMessage]] = {
    import SlackSearchRequest._
    val after = lastMessageTimestamp.map(t => Query.after(t.toDateTime.toLocalDate.minusDays(2))) // 2 days buffer because UTC vs PST and strict after behavior
    val query = Query(Query.in(channelName), Query.hasLink, after)
    val pageSize = PageSize((limit getOrElse 100) min PageSize.max)
    FutureHelpers.foldLeftUntil(Stream.from(1).map(Page(_)))(Seq.empty[SlackMessage]) {
      case (previousMessages, nextPage) =>
        val request = SlackSearchRequest(query, Sort.ByTimestamp, SortDirection.Ascending, pageSize, nextPage)
        slackClient.searchMessages(token, request).map { response =>
          val allMessages = previousMessages ++ response.messages.matches.filterNot(m => lastMessageTimestamp.exists(m.timestamp <= _))
          val messages = limit.map(allMessages.take(_)) getOrElse allMessages
          val done = limit.exists(_ <= messages.length) || response.messages.paging.pages <= nextPage.page
          (messages, done)
        }
    }

  }
}
