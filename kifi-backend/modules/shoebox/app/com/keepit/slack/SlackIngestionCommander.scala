package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.{ RawBookmarkRepresentation, KeepInterner }
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
  val ingestionPeriod = Period.minutes(10)
  val retryPeriod = Period.minutes(1)
  val recoveryPeriod = Period.minutes(30)
  val maxConcurrency = 10
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
    libraryRepo: LibraryRepo,
    slackClient: SlackClient,
    urlClassifier: UrlClassifier,
    keepInterner: KeepInterner,
    clock: Clock,
    airbrake: AirbrakeNotifier,
    implicit val ec: ExecutionContext) {

  import SlackIngestionCommander._

  def ingestAll(): Future[Unit] = {
    FutureHelpers.doUntil {
      val (integrations, memberships) = db.readWrite { implicit session =>
        val integrationIds = integrationRepo.getRipeForIngestion(maxConcurrency, recoveryPeriod)
        integrationRepo.markAsIngesting(integrationIds: _*)
        val integrationsByIds = integrationRepo.getByIds(integrationIds.toSet)
        val integrations = integrationIds.map(integrationsByIds(_))
        val memberships = slackRepo.getBySlackUserIds(integrations.map(_.slackUserId).toSet)
        (integrations, memberships)
      }
      val futureIngestions: Seq[Future[Unit]] = integrations.map { integration =>
        memberships.get(integration.slackUserId).flatMap(m => m.token.map((_, m.scopes))) match {
          case Some((token, scopes)) if scopes.contains(SlackAuthScope.SearchRead) => doIngest(token, integration).imap(_ => ()) recover { case _ => () }
          case _ =>
            db.readWrite { implicit session =>
              integrationRepo.markIngestionComplete(integration.id.get, None, None) // disable ingestion, todo(Léo): perhaps this should be handled in doIngest
            }
            Future.successful(())
        }
      }
      Future.sequence(futureIngestions).imap(_.isEmpty)
    }
  }

  private def doIngest(token: SlackAccessToken, integration: SlackChannelToLibrary): Future[Option[SlackMessageTimestamp]] = {
    val ingestionStartedAt = clock.now()
    val batchSize = 25
    FutureHelpers.foldLeftUntil(Stream.continually(()))(integration.lastMessageTimestamp) {
      case (lastMessageTimestamp, _) =>
        getLatestMessagesWithLinks(token, integration.slackChannelName, integration.lastIngestedAt, lastMessageTimestamp, Some(batchSize)).map { messages =>
          val newLastMessageTimestamp = ingestMessages(integration, messages)
          (newLastMessageTimestamp, newLastMessageTimestamp.isEmpty)
        }
    } andThen {
      case result =>
        val (lastIngestedAt, nextIngestionAt) = result match {
          case Success(_) => (Some(ingestionStartedAt), Some(ingestionStartedAt plus ingestionPeriod))
          case Failure(error) =>
            airbrake.notify(s"Slack ingestion failed: $integration", error)
            val nextIngestionAt = error match {
              case SlackAPIFailure(_, SlackAPIFailure.Error.invalidAuth, _) => None
              case _ => Some(ingestionStartedAt plus retryPeriod)
            }
            (None, nextIngestionAt)
        }
        db.readWrite { implicit session => integrationRepo.markIngestionComplete(integration.id.get, lastIngestedAt, nextIngestionAt) }
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

  private def toRawBookmarks(message: SlackMessage): Seq[RawBookmarkRepresentation] = {
    if (SlackUsername.doNotIngest.contains(message.username)) Seq.empty[RawBookmarkRepresentation]
    else message.attachments.flatMap { attachment => // todo(Léo): messages can have links but no attachment
      (attachment.title.flatMap(_.link) orElse attachment.fromUrl).collect {
        case url if !urlClassifier.isSocialActivity(url) =>
          RawBookmarkRepresentation(
            title = attachment.title.map(_.value),
            url = url,
            isPrivate = None,
            canonical = None,
            openGraph = None,
            keptAt = None, // todo(Léo): we do not get back a valid keptAt from Slack
            sourceAttribution = Some(SlackAttribution(message)),
            note = None
          )
      }
    }
  }

  private def getLatestMessagesWithLinks(token: SlackAccessToken, channelName: SlackChannelName, lastIngestedAt: Option[DateTime], lastMessageTimestamp: Option[SlackMessageTimestamp], limit: Option[Int]): Future[Seq[SlackMessage]] = {
    import SlackSearchRequest._
    val query = Query(Query.in(channelName), Query.hasLink, lastIngestedAt.map(t => Query.after(t.toLocalDate.minusDays(1))))
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
