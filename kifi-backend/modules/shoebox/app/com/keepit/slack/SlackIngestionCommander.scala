package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.{ PermissionCommander, RawBookmarkRepresentation, KeepInterner }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import com.keepit.common.util.UrlClassifier
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.slack.models._
import org.joda.time.DateTime
import org.joda.time.Period
import com.keepit.common.time._

import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object SlackIngestionCommander {
  val delayBetweenIngestions = Period.minutes(10)
  val retryDelay = Period.minutes(1)
  val ingestionGracePeriod = Period.minutes(30)
  val maxConcurrency = 10

  val slackLinkPattern = """<(.*?)(?:\|(.*?))?>""".r
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
    implicit val ec: ExecutionContext) extends SlackIngestionCommander {

  import SlackIngestionCommander._

  def ingestAll(): Future[Unit] = {
    FutureHelpers.doUntil {
      val (integrations, slackMemberships, isAllowed) = db.readWrite { implicit session =>
        val integrationIds = integrationRepo.getRipeForIngestion(maxConcurrency, ingestionGracePeriod)
        integrationRepo.markAsIngesting(integrationIds: _*)
        val integrationsByIds = integrationRepo.getByIds(integrationIds.toSet)
        val integrations = integrationIds.map(integrationsByIds(_))
        val slackMemberships = slackRepo.getBySlackUserIds(integrations.map(_.slackUserId).toSet)
        val isAllowed = integrations.map { integration =>
          integration.id.get -> permissionCommander.getLibraryPermissions(integration.libraryId, Some(integration.ownerId)).contains(LibraryPermission.ADD_KEEPS)
        }.toMap
        (integrations, slackMemberships, isAllowed)

      }
      val futureIngestions: Seq[Future[Unit]] = integrations.map {
        case integration if isAllowed(integration.id.get) =>
          slackMemberships.get(integration.slackUserId).flatMap(m => m.token.map((_, m.scopes))) match {
            case Some((token, scopes)) if scopes.contains(SlackAuthScope.SearchRead) => doIngest(token, integration).imap(_ => ()) recover { case _ => () }
            case _ =>
              airbrake.notify(s"Found broken Slack integration: $integration")
              db.readWrite { implicit session =>
                integrationRepo.updateAfterIngestion(integration.id.get, None, SlackIntegrationStatus.Broken) // disable ingestion, todo(Léo): perhaps this should be handled in doIngest
              }
              Future.successful(())
          }
        case forbiddenIntegration =>
          airbrake.notify(s"Turning off forbidden Slack integration: $forbiddenIntegration")
          db.readWrite { implicit session =>
            integrationRepo.updateAfterIngestion(forbiddenIntegration.id.get, None, SlackIntegrationStatus.Off) // disable ingestion, todo(Léo): perhaps this should be handled in doIngest
          }
          Future.successful(())
      }
      Future.sequence(futureIngestions).imap(_.isEmpty)
    }
  }

  private def doIngest(token: SlackAccessToken, integration: SlackChannelToLibrary): Future[Option[SlackMessageTimestamp]] = {
    val ingestionStartedAt = clock.now()
    val batchSize = 25
    FutureHelpers.foldLeftUntil(Stream.continually(()))(integration.lastMessageTimestamp) {
      case (lastMessageTimestamp, _) =>
        getLatestMessagesWithLinks(token, integration.slackChannelName, lastMessageTimestamp, Some(batchSize)).map { messages =>
          val newLastMessageTimestamp = ingestMessages(integration, messages)
          (newLastMessageTimestamp, newLastMessageTimestamp.isEmpty)
        }
    } andThen {
      case result =>
        val (nextIngestionAt, status) = result match {
          case Success(_) => (Some(ingestionStartedAt plus delayBetweenIngestions), integration.status)
          case Failure(error) =>
            airbrake.notify(s"Slack ingestion failed: $integration", error)
            val (nextIngestionAt, status) = error match {
              case SlackAPIFailure(_, SlackAPIFailure.Error.invalidAuth, _) => (None, SlackIntegrationStatus.Broken)
              case _ => (Some(ingestionStartedAt plus retryDelay), integration.status)
            }
            (nextIngestionAt, status)
        }
        db.readWrite { implicit session => integrationRepo.updateAfterIngestion(integration.id.get, nextIngestionAt, status) }
    }
  }

  private def ingestMessages(integration: SlackChannelToLibrary, messages: Seq[SlackMessage]): Option[SlackMessageTimestamp] = {
    val lastMessageTimestamp = messages.map(_.timestamp).maxOpt
    val rawBookmarks = messages.flatMap(toRawBookmarks).distinctBy(_.url)
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
      val linksFromText = slackLinkPattern.findAllMatchIn(message.text).flatMap { m =>
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
    val query = Query(Query.in(channelName), Query.hasLink, lastMessageTimestamp.map(t => Query.after(t.toDateTime.toLocalDate.minusDays(1))))
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
