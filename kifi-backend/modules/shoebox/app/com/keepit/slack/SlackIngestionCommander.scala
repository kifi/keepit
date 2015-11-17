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
  val nextIngestionDelayAfterFailure = Period.minutes(5)
  val nextIngestionDelayWithoutNewMessages = Period.minutes(10)
  val nextIngestionDelayAfterNewMessages = Period.minutes(1)

  val ingestionTimeout = Period.minutes(30)
  val integrationBatchSize = 10
  val messageBatchSize = 25

  val slackLinkPattern = """<(.*?)(?:\|(.*?))?>""".r

  case class BrokenSlackIntegration(integration: SlackIntegration, token: Option[SlackAccessToken], cause: Option[SlackAPIFailure]) extends Exception(s"Found a broken Slack integration: token->$token, integration->$integration, cause->$cause")
  case class ForbiddenSlackIntegration(integration: SlackIntegration) extends Exception(s"Found a forbidden Slack integration: $integration")
}

@ImplementedBy(classOf[SlackIngestionCommanderImpl])
trait SlackIngestionCommander {
  def ingestAll(): Future[Unit]
}

@Singleton
class SlackIngestionCommanderImpl @Inject() (
    db: Database,
    integrationRepo: SlackChannelToLibraryRepo,
    slackRepo: SlackTeamMembershipRepo,
    permissionCommander: PermissionCommander,
    libraryRepo: LibraryRepo,
    slackClient: SlackClient,
    urlClassifier: UrlClassifier,
    keepInterner: KeepInterner,
    clock: Clock,
    airbrake: AirbrakeNotifier,
    implicit val ec: ExecutionContext) extends SlackIngestionCommander with Logging {

  import SlackIngestionCommander._

  def ingestAll(): Future[Unit] = {
    FutureHelpers.doUntil {
      val (integrations, isAllowed, getToken) = db.readWrite { implicit session =>
        val integrationIds = integrationRepo.getRipeForIngestion(integrationBatchSize, ingestionTimeout)
        integrationRepo.markAsIngesting(integrationIds: _*)
        val integrationsByIds = integrationRepo.getByIds(integrationIds.toSet)
        val integrations = integrationIds.map(integrationsByIds(_))

        val isAllowed = integrations.map { integration =>
          integration.id.get -> permissionCommander.getLibraryPermissions(integration.libraryId, Some(integration.ownerId)).contains(LibraryPermission.ADD_KEEPS)
        }.toMap

        val getToken = {
          val slackMemberships = slackRepo.getBySlackUserIds(integrations.map(_.slackUserId).toSet)
          integrations.map { integration =>
            integration.id.get -> slackMemberships.get(integration.slackUserId).map(m => (m.token, m.scopes)).collect {
              case (Some(token), scopes) if scopes.contains(SlackAuthScope.SearchRead) => token
            }
          }.toMap
        }

        (integrations, isAllowed, getToken)
      }
      val allIngestedFuture = FutureHelpers.sequentialExec(integrations) {
        case integration => ingestMaybe(integration, isAllowed, getToken).imap(_ => ()).recover { case _ => () }
      }
      allIngestedFuture.imap(_ => integrations.isEmpty)
    }
  }

  private def ingestMaybe(integration: SlackChannelToLibrary, isAllowed: Id[SlackChannelToLibrary] => Boolean, getAuthToken: Id[SlackChannelToLibrary] => Option[SlackAccessToken]): Future[Option[SlackMessageTimestamp]] = {
    val futureIngestionMaybe = {
      if (isAllowed(integration.id.get)) {
        getAuthToken(integration.id.get) match {
          case Some(token) => doIngest(token, integration)
          case None => Future.failed(BrokenSlackIntegration(integration, None, None))
        }
      } else {
        Future.failed(ForbiddenSlackIntegration(integration))
      }
    }

    futureIngestionMaybe andThen { case result =>
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
          airbrake.notify(s"Failed to ingest from Slack via integration ${integration.id.get}", error)
          (Some(now plus nextIngestionDelayAfterFailure), None)
      }
      db.readWrite { implicit session =>
        integrationRepo.updateAfterIngestion(integration.id.get, nextIngestionAt, updatedStatus getOrElse integration.status)
      }
    }
  }

  private def doIngest(token: SlackAccessToken, integration: SlackChannelToLibrary): Future[Option[SlackMessageTimestamp]] = {
    FutureHelpers.foldLeftUntil(Stream.continually(()))(integration.lastMessageTimestamp) {
      case (lastMessageTimestamp, ()) =>
        getLatestMessagesWithLinks(token, integration.slackChannelName, lastMessageTimestamp, Some(messageBatchSize)).map { messages =>
          val newLastMessageTimestamp = ingestMessages(integration, messages)
          (newLastMessageTimestamp, newLastMessageTimestamp.isEmpty)
        }
    } recoverWith {
      case failure @ SlackAPIFailure(_, SlackAPIFailure.Error.invalidAuth, _) => Future.failed(BrokenSlackIntegration(integration, Some(token), Some(failure)))
    }
  }

  private def ingestMessages(integration: SlackChannelToLibrary, messages: Seq[SlackMessage]): Option[SlackMessageTimestamp] = {
    log.info(s"[SLACK-INGEST] Ingesting links from ${messages.length} messages from ${integration.slackChannelName.value}")
    val lastMessageTimestamp = messages.map(_.timestamp).maxOpt
    val rawBookmarks = messages.flatMap(toRawBookmarks).distinctBy(_.url)
    log.info(s"[SLACK-INGEST] Extracted these urls from those messages: ${rawBookmarks.map(_.url)}")
    // The following block sucks, it should all happen within the same session but that KeepInterner doesn't allow it
    val library = db.readOnlyMaster { implicit session => libraryRepo.get(integration.libraryId) }
    keepInterner.internRawBookmarks(rawBookmarks, integration.ownerId, library, KeepSource.slack)(HeimdalContext.empty)
    lastMessageTimestamp.foreach { timestamp =>
      db.readWrite { implicit session =>
        integrationRepo.updateLastMessageTimestamp(integration.id.get, timestamp)
      }
    }
    lastMessageTimestamp
  }

  private def doNotIngest(message: SlackMessage): Boolean = message.userId.value.trim.isEmpty || SlackUsername.doNotIngest.contains(message.username)
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
        case url if !urlClassifier.isSocialActivity(url) =>
          val title = linksFromText.get(url).flatten orElse linksFromAttachments.get(url).flatMap(_.title.map(_.value))
          RawBookmarkRepresentation(
            title = title,
            url = url,
            isPrivate = None,
            canonical = None,
            openGraph = None,
            keptAt = Some(message.timestamp.toDateTime),
            sourceAttribution = Some(SlackAttribution(message)),
            note = None
          )
      }
    }
  }

  private def getLatestMessagesWithLinks(token: SlackAccessToken, channelName: SlackChannelName, lastMessageTimestamp: Option[SlackMessageTimestamp], limit: Option[Int]): Future[Seq[SlackMessage]] = {
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
}
