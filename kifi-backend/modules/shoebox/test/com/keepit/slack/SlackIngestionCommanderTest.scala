package com.keepit.slack

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.{ FutureHelpers, FakeExecutionContextModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.SlackChannelToLibraryFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.slack.models.SlackSearchRequest.{ Sort, Page, PageSize, Query }
import com.keepit.slack.models.SlackSearchResponse.{ Paging, Messages }
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class SlackIngestionCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeSocialGraphModule(),
    FakeClockModule()
  )

  "SlackIngestionCommander" should {
    "create keeps for integrations that need to be processed" in {
      "work" in {
        withDb(modules: _*) { implicit injector =>
          val now = inject[FakeClock].now.minusMinutes(1)
          val (user, lib, stm, integration) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(user).saved
            val lib = LibraryFactory.library().withOwner(user).saved
            val slackTeam = SlackTeamFactory.team()
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val stl = SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib).withChannel("#eng").withNextIngestionAt(now).on().saved
            (user, lib, stm, stl)
          }
          db.readOnlyMaster { implicit s =>
            val x = inject[SlackChannelToLibraryRepo].get(integration.id.get)
            x.lastIngestingAt must beNone
            x.lastIngestedAt must beNone
            ktlRepo.getCountByLibraryId(lib.id.get) === 1
          }

          val query = Query(Query.in(integration.slackChannelName), Query.hasLink)
          val request = SlackSearchRequest(query, Sort.ByTimestamp, SlackSearchRequest.SortDirection.Ascending, PageSize(10))
          slackClient.enqueueFakeSearch(SlackSearchResponse(
            query, Messages(10, Paging(10, 10, 0, 1), Seq(SlackMessage(
              messageType = SlackMessageType("asdf"),
              userId = stm.slackUserId,
              username = stm.slackUsername,
              timestamp = SlackMessageTimestamp("1442527939.000076"),
              channel = SlackChannel(SlackChannelId("C123123"), SlackChannelName("#eng")),
              text = "<http://www.google.com|Google>",
              attachments = Seq.empty,
              permalink = "https://www.slack.com/permlink"
            )))
          ))
          val resFut = inject[SlackIngestionCommander].ingestAll()
          val res = Await.result(resFut, Duration.Inf)

          db.readOnlyMaster { implicit s =>
            val x = inject[SlackChannelToLibraryRepo].get(integration.id.get)
            x.lastIngestingAt must beNone
            x.lastIngestedAt must beSome
            ktlRepo.getCountByLibraryId(lib.id.get) === 1
          }
          1 === 1
        }
      }
    }
  }
}
