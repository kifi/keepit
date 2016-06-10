package com.keepit.commanders

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.{ RawSlackAttribution, UserFactory, KeepFactory }
import com.keepit.slack.models._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.social.Author
import com.keepit.test.ShoeboxTestInjector
import net.codingwell.scalaguice.ScalaModule
import org.apache.commons.lang3.RandomStringUtils
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.JsNull

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class KeepSourceCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val ctxt = HeimdalContext.empty
  val modules: Seq[ScalaModule] = Seq(
    FakeExecutionContextModule()
  )

  "KeepSourceCommander" should {
    "reattribute keeps" in {
      "from a slack author" in {
        withDb(modules: _*) { implicit injector =>
          val slackUserId = SlackUserId("U4242424242") // This is the only important part
          val slackTeamId = SlackTeamId("T4242424242")
          def foreignMsgFrom(who: SlackUserId) = {
            SlackMessage(
              messageType = SlackMessageType("user"),
              userId = who,
              username = SlackUsername("@ryanpbrewster"),
              timestamp = SlackTimestamp("1234512345.0000"),
              channel = SlackChannelIdAndName(SlackChannelId("C12341234"), SlackChannelName("#pandatime")),
              text = s"random text = ${RandomStringUtils.randomAlphabetic(20)}",
              attachments = Seq.empty,
              permalink = "http://totallyfakelink.com",
              originalJson = JsNull
            )
          }

          val (user, domesticKeeps, foreignKeeps) = db.readWrite { implicit s =>
            val user = UserFactory.user().saved
            val domesticKeeps = KeepFactory.keeps(84).map(_.withUser(user).saved)

            val foreignKeeps = KeepFactory.keeps(71).map(_.withNoUser().saved)
            foreignKeeps.foreach { keep =>
              sourceAttributionRepo.intern(keep.id.get, RawSlackAttribution(foreignMsgFrom(slackUserId), slackTeamId))
            }

            val randoSlackId = SlackUserId("URANDORANDO")
            val randoKeeps = KeepFactory.keeps(49).map(_.withNoUser().saved)
            randoKeeps.foreach { keep =>
              sourceAttributionRepo.intern(keep.id.get, RawSlackAttribution(foreignMsgFrom(randoSlackId), slackTeamId))
            }

            (user, domesticKeeps, foreignKeeps)
          }
          val Seq(domesticIds, foreignIds) = Seq(domesticKeeps, foreignKeeps).map(_.map(_.id.get).toSet)

          // Initially, the user owns their domestic keeps only
          db.readOnlyMaster { implicit s =>
            keepRepo.getByUser(user.id.get).map(_.id.get).toSet === domesticIds
            ktuRepo.getAllByUserId(user.id.get).map(_.keepId).toSet === domesticIds
          }
          // Then we assign $slackId to that user
          val reattributedKeeps = sourceAttributionCommander.reattributeKeeps(Author.SlackUser(slackTeamId, slackUserId), user.id.get)
          reattributedKeeps === foreignIds
          db.readOnlyMaster { implicit s =>
            keepRepo.getByUser(user.id.get).map(_.id.get).toSet === domesticIds ++ foreignIds
            ktuRepo.getAllByUserId(user.id.get).map(_.keepId).toSet === domesticIds ++ foreignIds
          }
          1 === 1
        }
      }
    }
  }

}
