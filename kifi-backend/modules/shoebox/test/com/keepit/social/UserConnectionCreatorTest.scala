package com.keepit.common.social

import java.io.File

import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{ EmailAddress, FakeMailModule, FakeOutbox }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.model._
import com.keepit.shoebox.FakeShoeboxServiceClientModule
import com.keepit.social.{ SocialId, SocialNetworks }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable._
import play.api.libs.json.Json

import util.Random

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

    "updateUserConnections" should {
      "not send notifications to users already unfriended on Kifi" in {
        withDb(modules: _*) { implicit injector =>
          val outbox = inject[FakeOutbox]
          val userConnRepo = inject[UserConnectionRepo]
          val scRepo = inject[SocialConnectionRepo]

          def generateUserAndSocialUser(uFirstName: String, uLastName: String) =
            db.readWrite { implicit rw =>
              val u1 = inject[UserRepo].save(User(
                firstName = uFirstName,
                lastName = uLastName,
                primaryEmail = Some(EmailAddress(uFirstName + uLastName + "@kifi.com"))
              ))

              val su1 = inject[SocialUserInfoRepo].save(SocialUserInfo(
                fullName = uFirstName,
                socialId = SocialId(Random.nextInt(9999999).toString),
                networkType = SocialNetworks.FACEBOOK
              ).withUser(u1))

              (u1, su1)
            }

          val (user1, socialUser1) = generateUserAndSocialUser("Josh", "McDade")
          val (user2, socialUser2) = generateUserAndSocialUser("Bill", "Clinton")
          val (user3, socialUser3) = generateUserAndSocialUser("Bob", "Dole")

          db.readWrite { implicit rw =>
            userConnRepo.save(UserConnection(
              user1 = user1.id.get,
              user2 = user2.id.get,
              state = UserConnectionStates.UNFRIENDED
            ))

            scRepo.save(SocialConnection(socialUser1 = socialUser1.id.get, socialUser2 = socialUser2.id.get))
            scRepo.save(SocialConnection(socialUser1 = socialUser2.id.get, socialUser2 = socialUser3.id.get))
            scRepo.save(SocialConnection(socialUser1 = socialUser1.id.get, socialUser2 = socialUser3.id.get))
          }

          inject[UserConnectionCreator].updateUserConnections(user1.id.get)

          // should be 1 instead of 2 b/c user1 and user2 are "unfriended"
          outbox.size === 1
        }
      }
    }

  }
}
