package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.Id
import com.keepit.slack.models._
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime

// Random AlphaNumeric
object ran { def apply(n: Int) = RandomStringUtils.randomAlphanumeric(n) }
// Random Alphabetic
object ra { def apply(n: Int) = RandomStringUtils.randomAlphabetic(n) }

case class TestingSlackTeam(
  teamId: SlackTeamId,
  teamName: SlackTeamName)
object SlackTeamFactory {
  def team() = TestingSlackTeam(SlackTeamId(ran(10)), SlackTeamName(ra(10)))
}

object SlackTeamMembershipFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)
  def membership(): PartialSlackTeamMembership = {
    PartialSlackTeamMembership(SlackTeamMembership(
      userId = Id[User](idx.incrementAndGet()),
      slackUserId = SlackUserId(ran(10)),
      slackUsername = SlackUsername(ran(10)),
      slackTeamId = SlackTeamId(ran(10)),
      slackTeamName = SlackTeamName(ran(10)),
      token = Some(SlackAccessToken(ran(30))),
      scopes = SlackAuthScope.library
    ))
  }

  case class PartialSlackTeamMembership(stm: SlackTeamMembership) {
    def withUser(user: User) = this.copy(stm = stm.copy(userId = user.id.get))
    def withTeam(team: TestingSlackTeam) = this.copy(stm = stm.copy(slackTeamId = team.teamId, slackTeamName = team.teamName))
  }

  def memberships(count: Int) = List.fill(count)(membership())
}

object SlackIncomingWebhookFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)
  def webhook(): PartialSlackIncomingWebhook = {
    val teamStr = ran(10)
    val botStr = ran(10)
    PartialSlackIncomingWebhook(SlackIncomingWebhookInfo(
      ownerId = Id[User](idx.incrementAndGet()),
      slackUserId = SlackUserId(ran(10)),
      slackTeamId = SlackTeamId(teamStr),
      slackChannelId = None,
      webhook = SlackIncomingWebhook(
        channelName = SlackChannelName(ra(10)),
        url = s"https://hooks.slack.com/services/$teamStr/$botStr/${ran(10)}",
        configUrl = s"https://${ra(5)}.slack.com/services/$botStr"
      ),
      lastPostedAt = None
    ))
  }
  case class PartialSlackIncomingWebhook(siw: SlackIncomingWebhookInfo) {
    def withMembership(stm: SlackTeamMembership) = this.copy(siw = siw.copy(ownerId = stm.userId, slackTeamId = stm.slackTeamId, slackUserId = stm.slackUserId))
    def withChannelName(cn: String) = this.copy(siw = siw.copy(webhook = siw.webhook.copy(channelName = SlackChannelName(cn))))
  }
  def webhooks(count: Int) = List.fill(count)(webhook())
}

object SlackChannelToLibraryFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)
  def stl(): PartialSlackChannelToLibrary = {
    PartialSlackChannelToLibrary(SlackChannelToLibrary(
      ownerId = Id[User](idx.incrementAndGet()),
      slackUserId = SlackUserId(ran(10)),
      slackTeamId = SlackTeamId(ran(10)),
      slackChannelId = None,
      slackChannelName = SlackChannelName(ra(10)),
      libraryId = Id[Library](idx.incrementAndGet())
    ))
  }
  case class PartialSlackChannelToLibrary(stl: SlackChannelToLibrary) {
    def withMembership(stm: SlackTeamMembership) = this.copy(stl = stl.copy(ownerId = stm.userId, slackTeamId = stm.slackTeamId, slackUserId = stm.slackUserId))
    def withLibrary(lib: Library) = this.copy(stl = stl.copy(libraryId = lib.id.get))
    def withChannel(cn: String) = this.copy(stl = stl.copy(slackChannelName = SlackChannelName(cn)))
    def withNextIngestionAt(time: DateTime) = this.copy(stl = stl.copy(nextIngestionAt = Some(time)))
    def on() = this.copy(stl = stl.withStatus(SlackIntegrationStatus.On))
  }

  def stls(count: Int) = List.fill(count)(stl())
}

object LibraryToSlackChannelFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)
  def lts(): PartialLibraryToSlackChannel = {
    PartialLibraryToSlackChannel(LibraryToSlackChannel(
      ownerId = Id[User](idx.incrementAndGet()),
      slackUserId = SlackUserId(ran(10)),
      slackTeamId = SlackTeamId(ran(10)),
      slackChannelId = None,
      slackChannelName = SlackChannelName(ra(10)),
      libraryId = Id[Library](idx.incrementAndGet())
    ))
  }

  case class PartialLibraryToSlackChannel(lts: LibraryToSlackChannel) {
    def withMembership(stm: SlackTeamMembership) = this.copy(lts = lts.copy(ownerId = stm.userId, slackTeamId = stm.slackTeamId, slackUserId = stm.slackUserId))
    def withLibrary(lib: Library) = this.copy(lts = lts.copy(libraryId = lib.id.get))
    def withChannel(cn: String) = this.copy(lts = lts.copy(slackChannelName = SlackChannelName(cn)))
  }

  def ltss(count: Int) = List.fill(count)(lts())
}
