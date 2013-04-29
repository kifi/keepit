package com.keepit.common.social

import java.io.File

import org.joda.time.DateTime
import org.specs2.mutable._

import com.keepit.common.db.slick.Database
import com.keepit.inject.inject
import com.keepit.model._
import com.keepit.test.{FakeClock, EmptyApplication}

import play.api.Play.current
import play.api.libs.json.Json
import play.api.test.Helpers._

class UserConnectionCreatorTest extends Specification {


  "UserConnectionCreator" should {
    "create connections between friends" in {
      running(new EmptyApplication().withFakeStore) {

        /*
         * grab json
         * import friends (create SocialUserInfo records)
         * using json and one socialuserinfo, create connections
         *
         */
        val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/facebook_graph_eishay_min.json")).mkString)

        inject[Database].readWrite { implicit s =>
          val u = inject[UserRepo].save(User(firstName = "Andrew", lastName = "Conner"))
          inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Andrew Conner",
            socialId = SocialId("71105121"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(u))
        }

        inject[SocialUserImportFriends].importFriends(Seq(json))

        val (user, socialUserInfo) = inject[Database].readWrite { implicit c =>
          val user = inject[UserRepo].save(User(firstName = "Greg", lastName = "Smith"))
          val socialUserInfo = inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Greg Smith",
            socialId = SocialId("gsmith"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(user))
          (user, socialUserInfo)
        }

        val connections = inject[UserConnectionCreator].createConnections(socialUserInfo, Seq(json))

        connections.size === 12

        inject[Database].readWrite { implicit s =>
          val fortyTwoConnections = inject[SocialConnectionRepo].getFortyTwoUserConnections(user.id.get)
          val userConnections = inject[UserConnectionRepo].getConnectedUsers(user.id.get)
          fortyTwoConnections === userConnections
          userConnections.size === 1
        }
        inject[UserConnectionCreator].getConnectionsLastUpdated(user.id.get) must beSome
      }
    }

    "disable non existing connections" in {
      running(new EmptyApplication().withFakeStore) {

        val json1 = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/facebook_graph_eishay_min.json")).mkString)

        inject[SocialUserImportFriends].importFriends(Seq(json1))

        val socialUserInfo = inject[Database].readWrite { implicit c =>
          val user = inject[UserRepo].save(User(firstName = "fn1", lastName = "ln1"))
          inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Bob Smith",
            socialId = SocialId("bsmith"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(user))
        }

        val connections = inject[UserConnectionCreator].createConnections(socialUserInfo, Seq(json1))

        connections.size === 12

        val json2 = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/facebook_graph_eishay_super_min.json")).mkString)

        val connectionsAfter = inject[UserConnectionCreator].createConnections(socialUserInfo, Seq(json2))
        connectionsAfter.size === 5
      }
    }

  }
}
