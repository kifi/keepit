package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.controllers.website.DeepLinkRouter
import com.keepit.model._
import com.keepit.slack.models._
import com.kifi.macros.json

@json
case class LibraryToSlackIntegrationInfo(
  id: PublicId[LibraryToSlackChannel],
  status: SlackIntegrationStatus)

@json
case class SlackToLibraryIntegrationInfo(
  id: PublicId[SlackChannelToLibrary],
  status: SlackIntegrationStatus)

@json
case class LibrarySlackIntegrationInfo(
  teamName: SlackTeamName,
  channelName: SlackChannelName,
  toSlack: Option[LibraryToSlackIntegrationInfo],
  fromSlack: Option[SlackToLibraryIntegrationInfo])

@json
case class LibrarySlackInfo(
  link: String,
  integrations: Seq[LibrarySlackIntegrationInfo])

@ImplementedBy(classOf[SlackCommanderImpl])
trait SlackCommander {
  // Open their own DB sessions, intended to be called directly from controllers
  def registerAuthorization(userId: Id[User], auth: SlackAuthorizationResponse, identity: SlackIdentifyResponse): Unit
  def setupIntegration(userId: Id[User], libId: Id[Library], webhook: SlackIncomingWebhook, identity: SlackIdentifyResponse): Unit

  // For use in the LibraryInfoCommander to send info down to clients
  def getSlackIntegrationsForLibraries(userId: Id[User], libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Option[LibrarySlackInfo]]
}

@Singleton
class SlackCommanderImpl @Inject() (
  db: Database,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  channelToLibRepo: SlackChannelToLibraryRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  slackClient: SlackClient,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends SlackCommander {

  def registerAuthorization(userId: Id[User], auth: SlackAuthorizationResponse, identity: SlackIdentifyResponse): Unit = {
    require(auth.teamId == identity.teamId && auth.teamName == identity.teamName)
    db.readWrite { implicit s =>
      slackTeamMembershipRepo.internBySlackTeamAndUser(SlackTeamMembershipInternRequest(
        userId = userId,
        slackUserId = identity.userId,
        slackUsername = identity.userName,
        slackTeamId = auth.teamId,
        slackTeamName = auth.teamName,
        token = auth.accessToken,
        scopes = auth.scopes
      ))
      auth.incomingWebhook.foreach { webhook =>
        slackIncomingWebhookInfoRepo.save(SlackIncomingWebhookInfo(
          ownerId = userId,
          slackUserId = identity.userId,
          slackTeamId = identity.teamId,
          slackChannelId = None,
          webhook = webhook,
          lastPostedAt = None
        ))
      }
    }
  }

  def setupIntegration(userId: Id[User], libId: Id[Library], webhook: SlackIncomingWebhook, identity: SlackIdentifyResponse): Unit = {
    db.readWrite { implicit s =>
      libToChannelRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationRequest(
        userId = userId,
        libraryId = libId,
        slackUserId = identity.userId,
        slackTeamId = identity.teamId,
        slackChannelId = None,
        slackChannel = webhook.channelName
      ))
    }
  }

  def getSlackIntegrationsForLibraries(userId: Id[User], libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Option[LibrarySlackInfo]] = {
    libraryIds.map { libId =>
      val slackToLibs = channelToLibRepo.getActiveByOwnerAndLibrary(userId, libId)
      val libToSlacks = libToChannelRepo.getActiveByOwnerAndLibrary(userId, libId)
      val teamNamesByTeamId = (slackToLibs.map(_.slackTeamId) ++ libToSlacks.map(_.slackTeamId)).map { teamId =>
        val memberships = slackTeamMembershipRepo.getBySlackTeam(teamId)
        val teamName = memberships.head.slackTeamName
        assert(memberships.forall(_.slackTeamName == teamName)) // oh sweet jesus I hope so
        teamId -> teamName
      }.toMap

      val fromSlacksGrouped = slackToLibs.groupBy(x => (x.slackTeamId, x.slackChannelName)).map {
        case (key, fromSlacks) =>
          key -> fromSlacks.map { fs => SlackToLibraryIntegrationInfo(SlackChannelToLibrary.publicId(fs.id.get), fs.status) }
      }
      val toSlacksGrouped = libToSlacks.groupBy(x => (x.slackTeamId, x.slackChannelName)).map {
        case (key, toSlacks) =>
          key -> toSlacks.map { ts => LibraryToSlackIntegrationInfo(LibraryToSlackChannel.publicId(ts.id.get), ts.status) }
      }
      val integrations = (fromSlacksGrouped.keySet ++ toSlacksGrouped.keySet).map {
        case (teamId, channelName) =>
          LibrarySlackIntegrationInfo(
            teamName = teamNamesByTeamId(teamId),
            channelName = channelName,
            toSlack = toSlacksGrouped.get((teamId, channelName)).flatMap(_.headOption),
            fromSlack = fromSlacksGrouped.get((teamId, channelName)).flatMap(_.headOption)
          )
      }.toSeq.sortBy(x => (x.teamName.value, x.channelName.value))
      libId -> Some(LibrarySlackInfo(
        link = slackClient.generateAuthorizationRequest(SlackAuthScope.library, DeepLinkRouter.libraryLink(Library.publicId(libId))),
        integrations = integrations
      ))
    }.toMap
  }

}
