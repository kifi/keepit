package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.Id
import com.keepit.model.LibrarySpace.UserSpace
import com.keepit.slack.models._
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime

// Random AlphaNumeric
object ran { def apply(n: Int) = RandomStringUtils.randomAlphanumeric(n) }
// Random Alphabetic
object ra { def apply(n: Int) = RandomStringUtils.randomAlphabetic(n) }

case class TestingSlackUser(
  userId: SlackUserId,
  username: SlackUsername)
object SlackUserFactory {
  def user() = TestingSlackUser(SlackUserId(ran(10)), SlackUsername(ra(10)))
}

object SlackTeamMembershipFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)
  def membership(): PartialSlackTeamMembership = {
    val owner = Id[User](idx.incrementAndGet())
    PartialSlackTeamMembership(SlackTeamMembership(
      userId = Some(owner),
      slackUserId = SlackUserId(ran(10)),
      slackUsername = SlackUsername(ran(10)),
      slackTeamId = SlackTeamId(ran(10)),
      slackTeamName = SlackTeamName(ran(10)),
      kind = SlackAccountKind.User,
      tokenWithScopes = Some(SlackTokenWithScopes(SlackUserAccessToken(ran(30)), SlackAuthScope.newPush ++ SlackAuthScope.ingest)),
      slackUser = None
    ))
  }

  case class PartialSlackTeamMembership(stm: SlackTeamMembership) {
    def withUser(user: User) = this.copy(stm = stm.copy(userId = Some(user.id.get)))
    def withTeam(team: SlackTeam) = this.copy(stm = stm.copy(slackTeamId = team.slackTeamId, slackTeamName = team.slackTeamName))
    def withUsername(str: String) = this.copy(stm = stm.copy(slackUsername = SlackUsername(str)))
    def withScopes(scopes: Set[SlackAuthScope]) = this.copy(stm = stm.copy(tokenWithScopes = stm.tokenWithScopes.map(_.copy(scopes = scopes))))

    def withNextPersonalDigestAt(time: DateTime) = this.copy(stm = stm.withNextPersonalDigestAt(time))
  }

  def memberships(count: Int) = List.fill(count)(membership())
}

object SlackTeamFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)
  def team(): PartialSlackTeam = {
    PartialSlackTeam(SlackTeam(
      slackTeamId = SlackTeamId(ran(10)),
      slackTeamName = SlackTeamName(ran(10)),
      organizationId = None,
      generalChannelId = None,
      kifiBot = None
    ))
  }

  case class PartialSlackTeam(team: SlackTeam) {
    def withName(newName: String) = this.copy(team = team.copy(slackTeamName = SlackTeamName(newName)))
    def withOrg(org: Organization) = this.copy(team = team.withOrganizationId(Some(org.id.get)))
    def withKifiBot(botUserId: SlackUserId, token: SlackBotAccessToken) = this.copy(team = team.withKifiBotIfDefined(Some(KifiSlackBot(botUserId, token))))

    def withNoPersonalDigestsUntil(time: DateTime) = this.copy(team = team.withNoPersonalDigestsUntil(time))
  }
}

object SlackChannelFactory {
  def channel(): PartialSlackChannel = {
    PartialSlackChannel(SlackChannel(
      slackTeamId = SlackTeamId("T" + ra(8)),
      slackChannelId = SlackChannelId("C" + ra(8)),
      slackChannelName = SlackChannelName(ran(10))
    ))
  }

  case class PartialSlackChannel(channel: SlackChannel) {
    def withTeam(slackTeam: SlackTeam) = this.copy(channel = channel.copy(slackTeamId = slackTeam.slackTeamId))
    def withName(newName: String) = this.copy(channel = channel.copy(slackChannelName = SlackChannelName(newName)))
  }
}

object SlackIncomingWebhookFactory {
  def webhook(): PartialSlackIncomingWebhook = {
    val teamStr = ran(10)
    val botStr = ran(10)
    val slackChannelId = SlackChannelId("C" + ra(8))
    val slackChannelName = SlackChannelName(ra(10))
    PartialSlackIncomingWebhook(SlackIncomingWebhookInfo(
      slackUserId = SlackUserId(ran(10)),
      slackTeamId = SlackTeamId(teamStr),
      slackChannelId = slackChannelId,
      webhook = SlackIncomingWebhook(
        channelName = slackChannelName,
        channelId = slackChannelId,
        url = s"https://hooks.slack.com/services/$teamStr/$botStr/${ran(10)}",
        configUrl = s"https://${ra(5)}.slack.com/services/$botStr"
      ),
      lastPostedAt = None
    ))
  }
  case class PartialSlackIncomingWebhook(siw: SlackIncomingWebhookInfo) {
    def withMembership(stm: SlackTeamMembership) = this.copy(siw = siw.copy(slackTeamId = stm.slackTeamId, slackUserId = stm.slackUserId))
    def withChannel(channel: SlackChannel) = this.copy(siw = siw.copy(slackChannelId = channel.slackChannelId, webhook = siw.webhook.copy(channelId = channel.slackChannelId, channelName = channel.slackChannelName)))
  }
  def webhooks(count: Int) = List.fill(count)(webhook())
}

object SlackChannelToLibraryFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)
  def stl(): PartialSlackChannelToLibrary = {
    val owner = Id[User](idx.incrementAndGet())
    PartialSlackChannelToLibrary(SlackChannelToLibrary(
      space = LibrarySpace.fromUserId(Id(-idx.incrementAndGet())),
      slackUserId = SlackUserId(ran(10)),
      slackTeamId = SlackTeamId(ran(10)),
      slackChannelId = SlackChannelId("C" + ra(8)),
      libraryId = Id[Library](idx.incrementAndGet()),
      status = SlackIntegrationStatus.Off
    ))
  }
  case class PartialSlackChannelToLibrary(stl: SlackChannelToLibrary) {
    def withMembership(stm: SlackTeamMembership) = this.copy(stl = stl.copy(space = LibrarySpace.fromUserId(stm.userId.get), slackTeamId = stm.slackTeamId, slackUserId = stm.slackUserId))
    def withLibrary(lib: Library) = this.copy(stl = stl.copy(libraryId = lib.id.get))
    def withChannel(cn: SlackChannel) = this.copy(stl = stl.copy(slackChannelId = cn.slackChannelId))
    def withNextIngestionAt(time: DateTime) = this.copy(stl = stl.copy(nextIngestionAt = Some(time)))
    def on() = this.copy(stl = stl.withStatus(SlackIntegrationStatus.On))
    def withSpace(space: LibrarySpace) = this.copy(stl = stl.withSpace(space))
  }

  def stls(count: Int) = List.fill(count)(stl())
}

object LibraryToSlackChannelFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)
  def lts(): PartialLibraryToSlackChannel = {
    val owner = Id[User](idx.incrementAndGet())
    PartialLibraryToSlackChannel(LibraryToSlackChannel(
      space = LibrarySpace.fromUserId(Id(-idx.incrementAndGet())),
      slackUserId = SlackUserId(ran(10)),
      slackTeamId = SlackTeamId(ran(10)),
      slackChannelId = SlackChannelId("C" + ra(8)),
      libraryId = Id[Library](idx.incrementAndGet()),
      status = SlackIntegrationStatus.Off
    ))
  }

  case class PartialLibraryToSlackChannel(lts: LibraryToSlackChannel) {
    def withMembership(stm: SlackTeamMembership) = this.copy(lts = lts.copy(space = LibrarySpace.fromUserId(stm.userId.get), slackTeamId = stm.slackTeamId, slackUserId = stm.slackUserId))
    def withLibrary(lib: Library) = this.copy(lts = lts.copy(libraryId = lib.id.get))
    def withChannel(cn: SlackChannel) = this.copy(lts = lts.copy(slackChannelId = cn.slackChannelId))
    def withNextPushAt(time: DateTime) = this.copy(lts = lts.withNextPushAt(time))
    def on() = this.copy(lts = lts.withStatus(SlackIntegrationStatus.On))
    def withStatus(newStatus: SlackIntegrationStatus) = this.copy(lts = lts.copy(status = newStatus))
    def withSpace(space: LibrarySpace) = this.copy(lts = lts.withSpace(space))
  }

  def ltss(count: Int) = List.fill(count)(lts())
}
