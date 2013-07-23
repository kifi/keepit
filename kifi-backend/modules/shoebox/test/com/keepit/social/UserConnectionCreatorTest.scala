package com.keepit.common.social

import java.io.File

import org.specs2.mutable._

import com.keepit.common.db.slick.Database
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.model._
import com.keepit.shoebox.TestShoeboxServiceClientModule
import com.keepit.social.{SocialNetworks, SocialId}
import com.keepit.test.{ShoeboxApplicationInjector, ShoeboxApplication}

import play.api.libs.json.Json
import play.api.test.Helpers._

class UserConnectionCreatorTest extends Specification with ShoeboxApplicationInjector {

  val modules = Seq(FakeHttpClientModule(), ShoeboxFakeStoreModule(), TestShoeboxServiceClientModule())

  "UserConnectionCreator" should {
    "create connections between friends for social users and kifi users" in {
      running(new ShoeboxApplication(modules:_*)) {

        /*
         * grab json
         * import friends (create SocialUserInfo records)
         * using json and one socialuserinfo, create connections
         *
         */
        val json = Json.parse(io.Source.fromFile(new File("modules/shoebox/test/com/keepit/common/social/data/facebook_graph_eishay_min.json")).mkString)

        inject[Database].readWrite { implicit s =>
          val u = inject[UserRepo].save(User(firstName = "Andrew", lastName = "Conner"))
          inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Andrew Conner",
            socialId = SocialId("71105121"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(u))
        }

        val extractedFriends = inject[FacebookSocialGraph].extractFriends(json)

        inject[SocialUserImportFriends].importFriends(extractedFriends)

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

        inject[Database].readOnly { implicit s =>
          val fortyTwoConnections = inject[SocialConnectionRepo].getFortyTwoUserConnections(user.id.get)
          val userConnections = inject[UserConnectionRepo].getConnectedUsers(user.id.get)
          fortyTwoConnections === userConnections
          userConnections.size === 1
        }
        inject[UserConnectionCreator].getConnectionsLastUpdated(user.id.get) must beSome
      }
    }

    "disable non existing connections for social users but not kifi users" in {
      running(new ShoeboxApplication(modules:_*)) {

        val json1 = Json.parse(io.Source.fromFile(new File("modules/shoebox/test/com/keepit/common/social/data/facebook_graph_eishay_min.json")).mkString)

        inject[Database].readWrite { implicit s =>
          val u1 = inject[UserRepo].save(User(firstName = "Andrew", lastName = "Conner"))
          inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Andrew Conner",
            socialId = SocialId("71105121"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(u1))
          val u2 = inject[UserRepo].save(User(firstName = "Igor", lastName = "Perisic"))
          inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Igor Perisic",
            socialId = SocialId("28779"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(u2))
        }

        val extractedFriends = inject[FacebookSocialGraph].extractFriends(json1)
        inject[SocialUserImportFriends].importFriends(extractedFriends)

        val (user, socialUserInfo) = inject[Database].readWrite { implicit c =>
          val user = inject[UserRepo].save(User(firstName = "fn1", lastName = "ln1"))
          val socialUserInfo = inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Bob Smith",
            socialId = SocialId("bsmith"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(user))
          (user, socialUserInfo)
        }

        val connections = inject[UserConnectionCreator].createConnections(socialUserInfo,
          extractedFriends.map(_._1.socialId), SocialNetworks.FACEBOOK)

        connections.size === 12

        val json2 = Json.parse(io.Source.fromFile(new File("modules/shoebox/test/com/keepit/common/social/data/facebook_graph_eishay_super_min.json")).mkString)

        val extractedFriends2 = inject[FacebookSocialGraph].extractFriends(json2)

        val connectionsAfter = inject[UserConnectionCreator]
            .createConnections(socialUserInfo, extractedFriends2.map(_._1.socialId), SocialNetworks.FACEBOOK)

        inject[Database].readOnly { implicit s =>
          val fortyTwoConnections = inject[SocialConnectionRepo].getFortyTwoUserConnections(user.id.get)
          val userConnections = inject[UserConnectionRepo].getConnectedUsers(user.id.get)
          fortyTwoConnections.size === 1
          userConnections.size === 2
        }
        connectionsAfter.size === 5
      }
    }

  }
}
