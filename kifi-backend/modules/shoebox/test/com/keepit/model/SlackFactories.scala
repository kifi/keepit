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

case class TestingSlackTeam(
  teamId: SlackTeamId,
  teamName: SlackTeamName)
object SlackTeamFactory {
  def team() = TestingSlackTeam(SlackTeamId(ran(10)), SlackTeamName(ra(10)))
}

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
      userId = owner,
      slackUserId = SlackUserId(ran(10)),
      slackUsername = SlackUsername(ran(10)),
      slackTeamId = SlackTeamId(ran(10)),
      slackTeamName = SlackTeamName(ran(10)),
      token = Some(SlackAccessToken(ran(30))),
      scopes = SlackAuthScope.push ++ SlackAuthScope.ingest
    ))
  }

  case class PartialSlackTeamMembership(stm: SlackTeamMembership) {
    def withUser(user: User) = this.copy(stm = stm.copy(userId = user.id.get))
    def withTeam(team: TestingSlackTeam) = this.copy(stm = stm.copy(slackTeamId = team.teamId, slackTeamName = team.teamName))
    def withUsername(str: String) = this.copy(stm = stm.copy(slackUsername = SlackUsername(str)))
  }

  def memberships(count: Int) = List.fill(count)(membership())
}

object SlackIncomingWebhookFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)
  def webhook(): PartialSlackIncomingWebhook = {
    val teamStr = ran(10)
    val botStr = ran(10)
    PartialSlackIncomingWebhook(SlackIncomingWebhookInfo(
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
    def withMembership(stm: SlackTeamMembership) = this.copy(siw = siw.copy(slackTeamId = stm.slackTeamId, slackUserId = stm.slackUserId))
    def withChannelName(cn: String) = this.copy(siw = siw.copy(webhook = siw.webhook.copy(channelName = SlackChannelName(cn))))
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
      slackChannelId = None,
      slackChannelName = SlackChannelName(ra(10)),
      libraryId = Id[Library](idx.incrementAndGet())
    ))
  }
  case class PartialSlackChannelToLibrary(stl: SlackChannelToLibrary) {
    def withMembership(stm: SlackTeamMembership) = this.copy(stl = stl.copy(space = LibrarySpace.fromUserId(stm.userId), slackTeamId = stm.slackTeamId, slackUserId = stm.slackUserId))
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
    val owner = Id[User](idx.incrementAndGet())
    PartialLibraryToSlackChannel(LibraryToSlackChannel(
      space = LibrarySpace.fromUserId(Id(-idx.incrementAndGet())),
      slackUserId = SlackUserId(ran(10)),
      slackTeamId = SlackTeamId(ran(10)),
      slackChannelId = None,
      slackChannelName = SlackChannelName(ra(10)),
      libraryId = Id[Library](idx.incrementAndGet())
    ))
  }

  case class PartialLibraryToSlackChannel(lts: LibraryToSlackChannel) {
    def withMembership(stm: SlackTeamMembership) = this.copy(lts = lts.copy(space = LibrarySpace.fromUserId(stm.userId), slackTeamId = stm.slackTeamId, slackUserId = stm.slackUserId))
    def withLibrary(lib: Library) = this.copy(lts = lts.copy(libraryId = lib.id.get))
    def withChannel(cn: String) = this.copy(lts = lts.copy(slackChannelName = SlackChannelName(cn)))
    def withNextPushAt(time: DateTime) = this.copy(lts = lts.withNextPushAt(time))
    def withStatus(newStatus: SlackIntegrationStatus) = this.copy(lts = lts.copy(status = newStatus))
  }

  def ltss(count: Int) = List.fill(count)(lts())
}
