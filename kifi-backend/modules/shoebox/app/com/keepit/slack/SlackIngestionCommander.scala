package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.{ PermissionCommander, RawBookmarkRepresentation, KeepInterner }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.common.util.UrlClassifier
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.slack.models._
import org.joda.time.Period
import com.keepit.common.time._

import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object SlackIngestionCommander {
  val nextIngestionDelayAfterFailure = Period.minutes(10)
  val nextIngestionDelayWithoutNewMessages = Period.minutes(2)
  val nextIngestionDelayAfterNewMessages = Period.seconds(30)
  val maxIngestionDelayAfterCommand = Period.seconds(15)

  val ingestionTimeout = Period.minutes(30)
  val integrationBatchSize = 10
  val messageBatchSize = 25

  val slackLinkPattern = """<(.*?)(?:\|(.*?))?>""".r
}

@ImplementedBy(classOf[SlackIngestionCommanderImpl])
trait SlackIngestionCommander {
  def ingestAllDue(): Future[Unit]
  def ingestFromChannelPlease(teamId: SlackTeamId, channelId: SlackChannelId): Unit
}

@Singleton
class SlackIngestionCommanderImpl @Inject() (
    db: Database,
    integrationRepo: SlackChannelToLibraryRepo,
    slackTeamMembershipRepo: SlackTeamMembershipRepo,
    permissionCommander: PermissionCommander,
    libraryRepo: LibraryRepo,
    slackClient: SlackClientWrapper,
    urlClassifier: UrlClassifier,
    keepInterner: KeepInterner,
    clock: Clock,
    airbrake: AirbrakeNotifier,
    implicit val ec: ExecutionContext) extends SlackIngestionCommander with Logging {

  import SlackIngestionCommander._
  import SlackIntegration._

  def ingestAllDue(): Future[Unit] = {
    log.info("[SLACK-INGEST] Processing all due integrations.")
    FutureHelpers.doUntil {
      val (integrations, isAllowed, getTokenWithScopes) = db.readWrite { implicit session =>
        val integrationIds = integrationRepo.getRipeForIngestion(integrationBatchSize, ingestionTimeout)
        log.info(s"[SLACK-INGEST] Found ${integrationIds.length}/$integrationBatchSize integrations to process next.")
        integrationRepo.markAsIngesting(integrationIds: _*)
        val integrationsByIds = integrationRepo.getByIds(integrationIds.toSet)
        val integrations = integrationIds.map(integrationsByIds(_))

        val isAllowed = integrations.map { integration =>
          integration.id.get -> slackTeamMembershipRepo.getBySlackTeamAndUser(integration.slackTeamId, integration.slackUserId).exists { stm =>
            permissionCommander.getLibraryPermissions(integration.libraryId, stm.userId).contains(LibraryPermission.ADD_KEEPS)
          }
        }.toMap

        val getToken = {
          val slackMemberships = slackTeamMembershipRepo.getBySlackUserIds(integrations.map(_.slackUserId).toSet)
          integrations.map { integration =>
            integration.id.get -> slackMemberships.get(integration.slackUserId).flatMap(_.tokenWithScopes)
          }.toMap
        }

        (integrations, isAllowed, getToken)
      }
      log.info(s"[SLACK-INGEST] Now processing ${integrations.length} integrations.")
      val allIngestedFuture = FutureHelpers.sequentialExec(integrations) {
        case integration => ingestMaybe(integration, isAllowed, getTokenWithScopes).imap(_ => ()).recover {
          case error =>
            log.error(s"[SLACK-INGEST] Something went wrong", error)
            //airbrake.notify(s"[SLACK-INGEST] Something went wrong", error) // please fix do this doesn't send so aggressively
            ()
        }
      }
      allIngestedFuture.imap { _ =>
        log.info(s"[SLACK-INGEST] Done processing ${integrations.length} integrations.]")
        integrations.isEmpty
      }
    } recover {
      case error =>
        log.error(s"[SLACK-INGEST] Something went *very* wrong", error)
        airbrake.notify(s"[SLACK-INGEST] Something went *very* wrong", error)
        ()
    }
  }

  private def ingestMaybe(integration: SlackChannelToLibrary, isAllowed: Id[SlackChannelToLibrary] => Boolean, getTokenWithScopes: Id[SlackChannelToLibrary] => Option[SlackTokenWithScopes]): Future[Option[SlackTimestamp]] = {
    val futureIngestionMaybe = {
      if (isAllowed(integration.id.get)) {
        getTokenWithScopes(integration.id.get) match {
          case Some(tokenWithScopes) if SlackAuthScope.ingest subsetOf tokenWithScopes.scopes => doIngest(tokenWithScopes, integration)
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
          case Success(Some(_)) => (Some(now plus nextIngestionDelayAfterNewMessages), None)
          case Success(None) => (Some(now plus nextIngestionDelayWithoutNewMessages), None)
          case Failure(forbidden: ForbiddenSlackIntegration) =>
            airbrake.notify(s"Turning off forbidden Slack integration ${integration.id.get}.", forbidden)
            (None, Some(SlackIntegrationStatus.Off))
          case Failure(broken: BrokenSlackIntegration) =>
            airbrake.notify(s"Marking Slack integration ${integration.id.get} as broken.", broken)
            (None, Some(SlackIntegrationStatus.Broken))
          case Failure(error) =>
            //airbrake.notify(s"Failed to ingest from Slack via integration ${integration.id.get}", error) // please fix do this doesn't send so aggressively
            log.warn(s"Failed to ingest from Slack via integration ${integration.id.get}", error)
            (Some(now plus nextIngestionDelayAfterFailure), None)
        }
        db.readWrite { implicit session =>
          integrationRepo.updateAfterIngestion(integration.id.get, nextIngestionAt, updatedStatus getOrElse integration.status)
        }
    }
  }

  private def doIngest(tokenWithScopes: SlackTokenWithScopes, integration: SlackChannelToLibrary): Future[Option[SlackTimestamp]] = {
    FutureHelpers.foldLeftUntil(Stream.continually(()))(integration.lastMessageTimestamp) {
      case (lastMessageTimestamp, ()) =>
        getLatestMessagesWithLinks(tokenWithScopes.token, integration.slackChannelName, lastMessageTimestamp, Some(messageBatchSize)).flatMap { messages =>
          val (newLastMessageTimestamp, ingestedMessages) = ingestMessages(integration, messages)
          FutureHelpers.sequentialExec(ingestedMessages.toSeq.sortBy(_.timestamp)) { message =>
            slackClient.addReaction(tokenWithScopes.token, SlackReaction.checkMark, message.channel.id, message.timestamp) recover {
              case SlackAPIFailure(_, SlackAPIFailure.Error.alreadyReacted, _) => ()
            }
          } imap { _ =>
            (newLastMessageTimestamp, newLastMessageTimestamp.isEmpty)
          }
        } recoverWith {
          case failure @ SlackAPIFailure(_, SlackAPIFailure.Error.invalidAuth, _) => Future.failed(BrokenSlackIntegration(integration, Some(tokenWithScopes.token), Some(failure)))
        }
    }
  }

  private def ingestMessages(integration: SlackChannelToLibrary, messages: Seq[SlackMessage]): (Option[SlackTimestamp], Set[SlackMessage]) = {
    log.info(s"[SLACK-INGEST] Ingesting links from ${messages.length} messages from ${integration.slackChannelName.value}")
    val lastMessageTimestamp = messages.map(_.timestamp).maxOpt
    val rawBookmarks = messages.flatMap(toRawBookmarks).distinctBy(_.url)
    log.info(s"[SLACK-INGEST] Extracted these urls from those messages: ${rawBookmarks.map(_.url)}")
    // The following block sucks, it should all happen within the same session but that KeepInterner doesn't allow it
    val (userId, library) = db.readOnlyMaster { implicit session =>
      val userId = slackTeamMembershipRepo.getBySlackTeamAndUser(integration.slackTeamId, integration.slackUserId).flatMap(_.userId).getOrElse {
        val message = s"Could not find a valid SlackMembership for ${(integration.slackTeamId, integration.slackUserId)} for stl ${integration.id.get}"
        airbrake.notify(message)
        throw new IllegalStateException(message)
      }
      val library = libraryRepo.get(integration.libraryId)
      (userId, library)
    }
    val ingestedMessages = {
      val (_, failed) = keepInterner.internRawBookmarks(rawBookmarks, userId, library, KeepSource.slack)(HeimdalContext.empty)
      (rawBookmarks.toSet -- failed).flatMap(_.sourceAttribution.collect { case SlackAttribution(message) => message })
    }
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
    val pageSize = PageSize((limit getOrElse 100) max PageSize.max)
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

  def ingestFromChannelPlease(teamId: SlackTeamId, channelId: SlackChannelId): Unit = db.readWrite { implicit session =>
    integrationRepo.ingestFromChannelWithin(teamId, channelId, maxIngestionDelayAfterCommand)
  }
}
