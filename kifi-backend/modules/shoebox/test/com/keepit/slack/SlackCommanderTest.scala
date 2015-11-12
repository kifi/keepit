package com.keepit.slack

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.model.LibraryToSlackChannelFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class SlackCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeSocialGraphModule()
  )

  "SlackCommander" should {
    "serve up a user's library integrations" in {
      "work in the simplest case" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib, fromLib) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().saved
            val slackTeam = SlackTeamFactory.team()
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val fromLib = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).saved
            println(user, lib, slackTeam, stm, fromLib)
            (user, lib, fromLib)
          }
          val slackInfo = db.readOnlyMaster { implicit s =>
            slackCommander.getSlackIntegrationsForLibraries(user.id.get, Set(lib.id.get)).get(lib.id.get).get.get
          }
          println(user, lib, fromLib)
          println(slackInfo)
          1 === 1
        }
      }
    }
  }
}
