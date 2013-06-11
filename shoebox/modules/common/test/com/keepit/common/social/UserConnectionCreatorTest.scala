package com.keepit.common.social

import java.io.File

import org.specs2.mutable._

import com.keepit.common.db.slick.Database
import com.keepit.inject.inject
import com.keepit.model._
import com.keepit.test.EmptyApplication

import play.api.Play.current
import play.api.libs.json.Json
import play.api.test.Helpers._

class UserConnectionCreatorTest extends Specification {


  "UserConnectionCreator" should {
    "create connections between friends" in {
      running(new EmptyApplication().withFakeHttpClient()) {

        /*
         * grab json
         * import friends (create SocialUserInfo records)
         * using json and one socialuserinfo, create connections
         *
         */
        val json = Json.parse(io.Source.fromFile(new File("modules/common/test/com/keepit/common/social/facebook_graph_eishay_min.json")).mkString)

        inject[Database].readWrite { implicit s =>
          val u = inject[UserRepo].save(User(firstName = "Andrew", lastName = "Conner"))
          inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Andrew Conner",
            socialId = SocialId("71105121"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(u))
        }

        val extractedFriends = inject[FacebookSocialGraph].extractFriends(json)

        inject[SocialUserImportFriends].importFriends(extractedFriends, SocialNetworks.FACEBOOK)

        val (user, socialUserInfo) = inject[Database].readWrite { implicit c =>
          val user = inject[UserRepo].save(User(firstName = "Greg", lastName = "Smith"))
          val socialUserInfo = inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Greg Smith",
            socialId = SocialId("gsmith"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(user))
          (user, socialUserInfo)
        }

        val connections = inject[UserConnectionCreator].createConnections(socialUserInfo,
          extractedFriends.map(_._1.socialId), SocialNetworks.FACEBOOK)

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
      running(new EmptyApplication().withFakeHttpClient()) {

        val json1 = Json.parse(io.Source.fromFile(new File("modules/common/test/com/keepit/common/social/facebook_graph_eishay_min.json")).mkString)

        val extractedFriends = inject[FacebookSocialGraph].extractFriends(json1)
        inject[SocialUserImportFriends].importFriends(extractedFriends, SocialNetworks.FACEBOOK)

        val socialUserInfo = inject[Database].readWrite { implicit c =>
          val user = inject[UserRepo].save(User(firstName = "fn1", lastName = "ln1"))
          inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Bob Smith",
            socialId = SocialId("bsmith"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(user))
        }

        val connections = inject[UserConnectionCreator].createConnections(socialUserInfo,
          extractedFriends.map(_._1.socialId), SocialNetworks.FACEBOOK)

        connections.size === 12

        val json2 = Json.parse(io.Source.fromFile(new File("modules/common/test/com/keepit/common/social/facebook_graph_eishay_super_min.json")).mkString)

        val extractedFriends2 = inject[FacebookSocialGraph].extractFriends(json2)

        val connectionsAfter = inject[UserConnectionCreator]
            .createConnections(socialUserInfo, extractedFriends2.map(_._1.socialId), SocialNetworks.FACEBOOK)
        connectionsAfter.size === 5
      }
    }

  }
}
