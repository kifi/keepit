package com.keepit.slack

import com.google.inject.Inject
import com.keepit.slack.models.SlackErrorCode._
import com.keepit.commanders.{ OrganizationInfoCommander, KeepInterner, PermissionCommander, RawBookmarkRepresentation }
import com.keepit.common.akka.{ SafeFuture, FortyTwoActor }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.{ DescriptionElements, UrlClassifier }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.OrganizationSpace
import com.keepit.model._
import com.keepit.slack.models.SlackIntegration.{ BrokenSlackIntegration, ForbiddenSlackIntegration }
import com.keepit.slack.models._
import com.kifi.juggle._
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.Period
import play.api.libs.json.{ JsValue, Json }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object SlackIngestionConfig {
  val nextIngestionDelayAfterFailure = Period.minutes(15)
  val nextIngestionDelayWithoutNewMessages = Period.minutes(5)
  val nextIngestionDelayAfterNewMessages = Period.minutes(2)
  val maxIngestionDelayAfterCommand = Period.seconds(15)

  val ingestionTimeout = Period.minutes(30)
  val minChannelIngestionConcurrency = 5
  val maxChannelIngestionConcurrency = 10

  val messagesPerRequest = 10
  val messagesPerIngestion = 50

  val slackLinkPattern = """<(.*?)(?:\|(.*?))?>""".r
}

class SlackIngestingActor @Inject() (
  db: Database,
  integrationRepo: SlackChannelToLibraryRepo,
  slackChannelRepo: SlackChannelRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  organizationInfoCommander: OrganizationInfoCommander,
  slackTeamRepo: SlackTeamRepo,
  permissionCommander: PermissionCommander,
  libraryRepo: LibraryRepo,
  slackClient: SlackClientWrapper,
  urlClassifier: UrlClassifier,
  keepInterner: KeepInterner,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  slackOnboarder: SlackOnboarder,
  slackPushForKeepRepo: SlackPushForKeepRepo,
  slackPushForMessageRepo: SlackPushForMessageRepo,
  orgConfigRepo: OrganizationConfigurationRepo,
  userValueRepo: UserValueRepo,
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
      integrationRepo.markAsIngesting(integrationIds: _*)
      Future.successful(integrationIds)
    }
  }

  protected def processTasks(integrationIds: Seq[Id[SlackChannelToLibrary]]): Map[Id[SlackChannelToLibrary], Future[Unit]] = {
    val (integrationsByIds, isAllowed, getIntegrationInfo) = db.readOnlyMaster { implicit session =>
      val integrationsByIds = integrationRepo.getByIds(integrationIds.toSet)

      val slackIdentities = integrationsByIds.values.map { sctl => (sctl.slackTeamId, sctl.slackUserId) }
      val slackMembershipsByIdentity = slackTeamMembershipRepo.getBySlackIdentities(slackIdentities.toSet)

      val isAllowed = integrationsByIds.map {
        case (integrationId, integration) =>
          integrationId -> slackMembershipsByIdentity.get((integration.slackTeamId, integration.slackUserId)).exists { stm =>
            permissionCommander.getLibraryPermissions(integration.libraryId, stm.userId).contains(LibraryPermission.ADD_KEEPS)
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
            val userTokenWithScopes = slackMembershipsByIdentity.get((integration.slackTeamId, integration.slackUserId)).flatMap(_.tokenWithScopes)
            val settings = slackTeam.organizationId.flatMap(orgId => settingsByOrgIds.get(orgId))
            integrationId -> (slackTeam, slackChannel, userTokenWithScopes, settings)
        }
      }

      (integrationsByIds, isAllowed, getIntegrationInfo)
    }
    integrationsByIds.map {
      case (integrationId, integration) =>
        integrationId -> ingestMaybe(integration, isAllowed, getIntegrationInfo).imap(_ => ())
    }
  }

  private def ingestMaybe(integration: SlackChannelToLibrary, isAllowed: Id[SlackChannelToLibrary] => Boolean, getIntegrationInfo: Id[SlackChannelToLibrary] => (SlackTeam, SlackChannel, Option[SlackTokenWithScopes], Option[OrganizationSettings])): Future[Option[SlackTimestamp]] = {
    val (team, channel, tokenOpt, settingsOpt) = getIntegrationInfo(integration.id.get)
    val futureIngestionMaybe = {
      if (isAllowed(integration.id.get)) {
        tokenOpt match {
          case Some(tokenWithScopes) if SlackAuthScope.ingest subsetOf tokenWithScopes.scopes => doIngest(team, channel, tokenWithScopes, settingsOpt, integration)
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
              slackOnboarder.talkAboutIntegration(integration, channel)
            }
            val delay = lastMsgTimestamp match {
              case Some(newTimestamp) if !integration.lastMessageTimestamp.contains(newTimestamp) => nextIngestionDelayAfterNewMessages
              case _ => nextIngestionDelayWithoutNewMessages
            }
            (Some(now plus delay), None)
          case Failure(forbidden: ForbiddenSlackIntegration) =>
            slackLog.warn("Integration between", forbidden.integration.libraryId, "and", forbidden.integration.slackChannelId.value, "in team", forbidden.integration.slackTeamId.value, "is forbidden")
            (None, Some(SlackIntegrationStatus.Off))
          case Failure(broken: BrokenSlackIntegration) =>
            slackLog.warn("Integration between", broken.integration.libraryId, "and", broken.integration.slackChannelId.value, "in team", broken.integration.slackTeamId.value, "is broken")
            SafeFuture {
              val cause = broken.cause.map(_.toString).getOrElse("???")
              val (teamNameOpt, channelNameOpt) = db.readOnlyMaster { implicit s =>
                slackTeamRepo.getBySlackTeamId(integration.slackTeamId) -> slackChannelRepo.getByChannelId(integration.slackTeamId, integration.slackChannelId)
              }
              val teamName = teamNameOpt.map(_.slackTeamName.value).getOrElse(integration.slackTeamId.value)
              val channelName = channelNameOpt.map(_.slackChannelName.value).getOrElse(integration.slackChannelId.value)
              inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
                "Can't Ingest - Broken Slack integration of team", teamName, "channel", channelName, "cause", cause)))
            }
            (None, Some(SlackIntegrationStatus.Broken))
          case Failure(error) =>
            log.error(s"[SLACK-INGEST] Failed to ingest from Slack via integration ${integration.id.get}: ${error.getMessage}")
            (Some(now plus nextIngestionDelayAfterFailure), None)
        }
        db.readWrite { implicit session =>
          integrationRepo.updateAfterIngestion(integration.id.get, nextIngestionAt, updatedStatus getOrElse integration.status)
        }
    }
  }

  private def doIngest(team: SlackTeam, channel: SlackChannel, tokenWithScopes: SlackTokenWithScopes, settings: Option[OrganizationSettings], integration: SlackChannelToLibrary): Future[Option[SlackTimestamp]] = {
    val shouldAddReactions = settings.exists(_.settingFor(StaticFeature.SlackIngestionReaction).contains(StaticFeatureSetting.ENABLED))
    FutureHelpers.foldLeftUntil[Unit, Option[SlackTimestamp]](Stream.continually(()))(integration.lastMessageTimestamp) {
      case (lastMessageTimestamp, ()) =>
        getLatestMessagesWithLinks(tokenWithScopes.token, integration.slackTeamId, integration.slackChannelId, channel.slackChannelName, lastMessageTimestamp).flatMap { allMessages =>
          val validMessages = allMessages.filter(_.channel.id == integration.slackChannelId)
          val (newLastMessageTimestamp, ingestedMessages) = ingestMessages(integration, settings, validMessages)

          val futureReactions = if (shouldAddReactions) {
            FutureHelpers.sequentialExec(ingestedMessages.toSeq.sortBy(_.timestamp)) { message =>
              slackClient.addReaction(team.kifiBot.map(_.token) getOrElse tokenWithScopes.token, SlackReaction.robotFace, message.channel.id, message.timestamp) recover {
                case SlackErrorCode(ALREADY_REACTED) => ()
              }
            }
          } else Future.successful(Unit)
          futureReactions imap { _ =>
            (newLastMessageTimestamp orElse lastMessageTimestamp, newLastMessageTimestamp.isEmpty)
          }
        } recoverWith {
          case failure @ SlackErrorCode(INVALID_AUTH) => Future.failed(BrokenSlackIntegration(integration, Some(tokenWithScopes.token), Some(SlackFail.SlackResponse(failure))))
        }
    }
  }

  private def ingestMessages(integration: SlackChannelToLibrary, settings: Option[OrganizationSettings], messages: Seq[SlackMessage]): (Option[SlackTimestamp], Set[SlackMessage]) = {
    val slackIdentities = messages.map(_.userId).map(slackUserId => (integration.slackTeamId, slackUserId)).toSet
    val blacklist = settings.flatMap(_.settingFor(ClassFeature.SlackIngestionDomainBlacklist).collect { case blk: ClassFeature.Blacklist => blk })
    val (library, slackTeam, slackMemberships, pushedTimestamps) = db.readOnlyMaster { implicit s =>
      val lib = libraryRepo.get(integration.libraryId)
      val slackTeam = slackTeamRepo.getBySlackTeamId(integration.slackTeamId).getOrElse(throw new Exception(s"There is supposed to be a db-level integrity constraint for ${integration.slackTeamId}"))
      val slackMemberships = slackTeamMembershipRepo.getBySlackIdentities(slackIdentities)
      val pushedTimestamps = messages.map(_.timestamp).minOpt.fold(Set.empty[SlackTimestamp]) { minTimestamp =>
        slackPushForKeepRepo.getPushedTimestampsByChannel(integration.slackChannelId, minTimestamp) ++
          slackPushForMessageRepo.getPushedTimestampsByChannel(integration.slackChannelId, minTimestamp)
      }
      (lib, slackTeam, slackMemberships, pushedTimestamps)
    }
    // The following block sucks, it should all happen within the same session but that KeepInterner doesn't allow it
    val rawBookmarksByUser = messages.groupBy(msg => slackMemberships.get((integration.slackTeamId, msg.userId))).collect {
      case (membershipOpt, msgs) if !membershipOpt.exists(_.isBot) =>
        membershipOpt.flatMap(_.userId) -> msgs.flatMap(toRawBookmarks(_, slackTeam, blacklist, pushedTimestamps)).distinctBy(_.url)
    }
    val ingestedMessages = rawBookmarksByUser.flatMap {
      case (kifiUserOpt, rawBookmarks) =>
        val interned = keepInterner.internRawBookmarksWithStatus(rawBookmarks, kifiUserOpt, Some(library), usersAdded = Set.empty, KeepSource.Slack)(HeimdalContext.empty)
        (rawBookmarks.toSet -- interned.failures).flatMap(_.sourceAttribution.collect { case slack: RawSlackAttribution => slack.message })
    }.toSet
    airbrake.verify(ingestedMessages.forall(_.channel.id == integration.slackChannelId), s"Ingested a message from the wrong channel (integration ${integration.id.get}): ${ingestedMessages.filter(_.channel.id != integration.slackChannelId)}")
    // Record a bit of information based on the messages we ingested: the channel, and any slack members
    val now = clock.now
    db.readWrite { implicit s =>
      ingestedMessages.headOption.foreach { msg =>
        slackChannelRepo.getOrCreate(slackTeam.slackTeamId, msg.channel.id, msg.channel.name)
      }
      ingestedMessages.groupBy(_.userId).mapValuesStrict(_.maxBy(_.timestamp)).foreach {
        case (senderId, latestMsg) =>
          val (sender, isNew) = slackTeamMembershipRepo.internWithMessage(slackTeam, latestMsg)
          if (sender.lastPersonalDigestAt.isEmpty && latestMsg.timestamp.toDateTime.isAfter(integration.createdAt)) {
            slackTeamMembershipRepo.save(sender.scheduledForDigestAtLatest(now))
          }
      }
    }
    val lastMessageTimestamp = messages.map(_.timestamp).maxOpt
    lastMessageTimestamp.foreach { timestamp =>
      db.readWrite { implicit session =>
        integrationRepo.updateLastMessageTimestamp(integration.id.get, timestamp)
      }
    }
    (lastMessageTimestamp, ingestedMessages)
  }

  private def ignoreMessage(message: SlackMessage, userIdsToIgnore: Set[SlackUserId], timestampsToIgnore: Set[SlackTimestamp]): Boolean = {
    message.userId.value.trim.isEmpty ||
      userIdsToIgnore.contains(message.userId) ||
      timestampsToIgnore.contains(message.timestamp) ||
      SlackUsername.doNotIngest.contains(message.username)
  }
  private def ignoreUrl(url: String, blacklist: Option[ClassFeature.Blacklist]): Boolean = {
    blacklist.exists(l => SlackIngestingBlacklist.blacklistedUrl(url, l.entries.map(_.path))) ||
      urlClassifier.isSocialActivity(url) ||
      urlClassifier.isSlackFile(url) ||
      urlClassifier.isSlackArchivedMessage(url)
  }

  private def toRawBookmarks(message: SlackMessage, slackTeam: SlackTeam, blacklist: Option[ClassFeature.Blacklist], timestampsToIgnore: Set[SlackTimestamp]): Set[RawBookmarkRepresentation] = {
    if (ignoreMessage(message, slackTeam.kifiBot.map(_.userId).toSet, timestampsToIgnore)) Set.empty[RawBookmarkRepresentation]
    else {
      val linksFromText = slackLinkPattern.findAllMatchIn(message.text).toList.flatMap { m =>
        m.subgroups.map(maybeNullStr => Option(maybeNullStr).map(_.trim).filter(_.nonEmpty)) match {
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
        case url if !ignoreUrl(url, blacklist) =>
          val title = linksFromText.get(url).flatten orElse linksFromAttachments.get(url).flatMap(_.title.map(_.value))
          RawBookmarkRepresentation(
            title = title.map(_.replaceAllLiterally("&amp;", "&")),
            url = url,
            canonical = None,
            openGraph = None,
            keptAt = Some(message.timestamp.toDateTime),
            sourceAttribution = Some(RawSlackAttribution(message, slackTeam.slackTeamId)),
            note = None
          )
      }
    }
  }

  private def getLatestMessagesWithLinks(token: SlackUserAccessToken, teamId: SlackTeamId, channelId: SlackChannelId, channelName: SlackChannelName, lastMessageTimestamp: Option[SlackTimestamp]): Future[Seq[SlackMessage]] = {
    import SlackSearchRequest._
    val after = lastMessageTimestamp.map(t => Query.after(t.toDateTime.toLocalDate.minusDays(2))) // 2 days buffer because UTC vs PST and strict after behavior
    val query = Query(Query.in(channelName), Query.hasLink, after)

    val bigPages = PageSize(messagesPerRequest)
    val tinyPages = PageSize(1)

    def getBatchedMessages(pageSize: PageSize, skipFailures: Boolean): Future[Seq[SlackMessage]] = FutureHelpers.foldLeftUntil(Stream.from(1).map(Page(_)))(Seq.empty[SlackMessage]) {
      case (previousMessages, nextPage) =>
        val request = SlackSearchRequest(query, Sort.ByTimestamp, SortDirection.Ascending, pageSize, nextPage)
        val futureResponse = {
          if (SlackChannelId.isPublic(channelId)) slackClient.searchMessagesHoweverPossible(teamId, request, preferredTokens = Seq(token))
          else slackClient.searchMessages(token, request)
        }
        futureResponse.map { response =>
          val allMessages = previousMessages ++ response.messages.matches.filterNot(m => lastMessageTimestamp.exists(m.timestamp <= _))
          val done = allMessages.length >= messagesPerIngestion || response.messages.paging.pages <= nextPage.page
          val messages = allMessages.take(messagesPerIngestion)
          (messages, done)
        }.recover {
          case SlackFail.MalformedPayload(payload) if skipFailures &&
            (payload \ "messages" \ "matches").asOpt[Seq[JsValue]].exists(_.forall(SlackMessage.weKnowWeCannotParse)) =>
            slackLog.info("Skipping known unparseable messages")
            (previousMessages, false)
        }
    }
    getBatchedMessages(bigPages, skipFailures = false).recoverWith {
      case SlackFail.MalformedPayload(payload) =>
        val key = RandomStringUtils.randomAlphabetic(5).toUpperCase
        slackLog.warn(s"[$key] Failed ingesting from $channelName with $bigPages because of malformed payload ${Json.stringify(payload).take(100)},retrying with $tinyPages")
        getBatchedMessages(tinyPages, skipFailures = true).andThen {
          case Success(_) => slackLog.info(s"[$key] That fixed it :+1:")
          case Failure(fail2) => slackLog.error(s"[$key] :scream: Failed with $tinyPages too, with error ${fail2.getMessage.take(100)}.")
        }
    }
  }
}
