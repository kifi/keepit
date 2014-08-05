package com.keepit.common.social

import java.io.File

import org.specs2.mutable._

import com.keepit.common.db.slick.Database
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.model._
import com.keepit.shoebox.FakeShoeboxServiceClientModule
import com.keepit.social.{ SocialNetworks, SocialId }
import com.keepit.test.{ ShoeboxTestInjector, ShoeboxApplicationInjector, ShoeboxApplication }
import com.keepit.eliza.FakeElizaServiceClientModule

import play.api.libs.json.Json
import play.api.test.Helpers._
import com.keepit.common.mail.{ FakeOutbox, EmailAddress, FakeMailModule }

class UserConnectionCreatorTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(FakeHttpClientModule(), FakeShoeboxStoreModule(), FakeShoeboxServiceClientModule(), FakeElizaServiceClientModule(), FakeMailModule())

  "UserConnectionCreator" should {
    "create connections between friends for social users and kifi users" in {
      withDb(modules: _*) { implicit injector =>

        val outbox = inject[FakeOutbox]

        /*
         * grab json
         * import friends (create SocialUserInfo records)
         * using json and one socialuserinfo, create connections
         *
         */
        val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/facebook_graph_eishay_min.json")).mkString)

        val socialUser = inject[Database].readWrite { implicit s =>
          val u = inject[UserRepo].save(User(firstName = "Andrew", lastName = "Conner"))

          inject[UserEmailAddressRepo].save(UserEmailAddress(userId = u.id.get, address = EmailAddress("andrew@gmail.com")))

          val su = inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Andrew Conner",
            socialId = SocialId("71105121"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(u))
          su
        }

        val extractedFriends = inject[FacebookSocialGraph].extractFriends(json)

        inject[SocialUserImportFriends].importFriends(socialUser, extractedFriends)

        val (user, socialUserInfo) = inject[Database].readWrite { implicit c =>
          val user = inject[UserRepo].save(User(firstName = "Greg", lastName = "Smith"))
          val socialUserInfo = inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Greg Smith",
            socialId = SocialId("gsmith"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(user))
          inject[UserEmailAddressRepo].save(UserEmailAddress(userId = user.id.get, address = EmailAddress("greg@gmail.com")))
          (user, socialUserInfo)
        }

        val connections = inject[UserConnectionCreator].createConnections(socialUserInfo,
          extractedFriends.map(_.socialId), SocialNetworks.FACEBOOK)

        connections.size === 12

        outbox.size === 1
        outbox(0).subject === "You are now friends with Greg Smith on Kifi!"
        outbox(0).to === Seq(EmailAddress("andrew@gmail.com"))

        inject[Database].readOnlyMaster { implicit s =>
          val fortyTwoConnections = inject[SocialConnectionRepo].getFortyTwoUserConnections(user.id.get)
          val userConnections = inject[UserConnectionRepo].getConnectedUsers(user.id.get)
          val connectionCount = inject[UserConnectionRepo].getConnectionCount(user.id.get)
          fortyTwoConnections === userConnections
          connectionCount === userConnections.size
          connectionCount === 1
        }
        inject[UserConnectionCreator].getConnectionsLastUpdated(user.id.get) must beSome
      }
    }

    "disable non existing connections for social users but not kifi users" in {
      withDb(modules: _*) { implicit injector =>

        val json1 = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/facebook_graph_eishay_min.json")).mkString)

        val sui1 = inject[Database].readWrite { implicit s =>
          val u1 = inject[UserRepo].save(User(firstName = "Andrew", lastName = "Conner"))
          val su1 = inject[SocialUserInfoRepo].save(SocialUserInfo(
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

          inject[UserEmailAddressRepo].save(UserEmailAddress(userId = u1.id.get, address = EmailAddress("andrew@gmail.com")))
          inject[UserEmailAddressRepo].save(UserEmailAddress(userId = u2.id.get, address = EmailAddress("igor@gmail.com")))

          su1
        }

        val extractedFriends = inject[FacebookSocialGraph].extractFriends(json1)
        inject[SocialUserImportFriends].importFriends(sui1, extractedFriends)

        val (user, socialUserInfo) = inject[Database].readWrite { implicit c =>
          val user = inject[UserRepo].save(User(firstName = "fn1", lastName = "ln1"))
          val socialUserInfo = inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Bob Smith",
            socialId = SocialId("bsmith"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(user))
          inject[UserEmailAddressRepo].save(UserEmailAddress(userId = user.id.get, address = EmailAddress("bob@gmail.com")))
          (user, socialUserInfo)
        }

        val connections = inject[UserConnectionCreator].createConnections(socialUserInfo,
          extractedFriends.map(_.socialId), SocialNetworks.FACEBOOK)

        connections.size === 12

        val json2 = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/facebook_graph_eishay_super_min.json")).mkString)

        val extractedFriends2 = inject[FacebookSocialGraph].extractFriends(json2)

        val connectionsAfter = inject[UserConnectionCreator]
          .createConnections(socialUserInfo, extractedFriends2.map(_.socialId), SocialNetworks.FACEBOOK)

        inject[Database].readOnlyMaster { implicit s =>
          val fortyTwoConnections = inject[SocialConnectionRepo].getFortyTwoUserConnections(user.id.get)
          val userConnections = inject[UserConnectionRepo].getConnectedUsers(user.id.get)
          val connectionCount = inject[UserConnectionRepo].getConnectionCount(user.id.get)
          fortyTwoConnections.size === 1
          connectionCount === userConnections.size
          connectionCount === 2
        }
        connectionsAfter.size === 5
      }
    }

  }
}
