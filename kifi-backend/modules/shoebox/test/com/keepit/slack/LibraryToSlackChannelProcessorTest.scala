package com.keepit.slack

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryToSlackChannelFactoryHelper._
import com.keepit.model.SlackChannelToLibraryFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class LibraryToSlackChannelProcessorTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeSocialGraphModule()
  )

  "LibraryToSlackChannelProcessorTestSlackCommander" should {
    "identify and process integrations" in {
      "unmark integrations when finished processing" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib, integration) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().saved
            val slackTeam = SlackTeamFactory.team()
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            (user, lib, lts)
          }
          db.readOnlyMaster { implicit s => inject[LibraryToSlackChannelRepo].get(integration.id.get).startedProcessingAt must beNone }

          val resFut = inject[LibraryToSlackChannelProcessor].processLibrary(lib.id.get)
          val res = Await.result(resFut, Duration.Inf)
          res.size === 1
          res.values.toList === List(false) // integration failed because there is no webhook!

          db.readOnlyMaster { implicit s => inject[LibraryToSlackChannelRepo].get(integration.id.get).startedProcessingAt must beNone }
        }
      }
    }
  }
}
