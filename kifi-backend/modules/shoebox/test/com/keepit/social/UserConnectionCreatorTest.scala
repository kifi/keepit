package com.keepit.common.social

import java.io.File

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.strings.UTF8
import com.keepit.common.concurrent.{ FakeExecutionContextModule, ExecutionContextModule }
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{ EmailAddress, FakeMailModule, FakeOutbox }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.shoebox.FakeShoeboxServiceClientModule
import com.keepit.social.{ SocialNetworkType, SocialId, SocialNetworks }
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.matcher.MatchResult
import org.specs2.mutable._
import play.api.libs.json.Json

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import util.Random

class UserConnectionCreatorTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeHttpClientModule(),
    FakeShoeboxStoreModule(),
    FakeShoeboxServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeMailModule(),
    FakeABookServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeSocialGraphModule()
  )

  "UserConnectionCreator" should {
    /*
     * grab json
     * import friends (create SocialUserInfo records)
     * using json and one socialuserinfo, create connections
     *
     */
    val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/facebook_graph_eishay_min.json"), UTF8).mkString)

    def setup(db: Database, network: SocialNetworkType)(implicit injector: Injector) = {
      val emailAddressRepo: UserEmailAddressRepo = inject[UserEmailAddressRepo]
      val (myUser, mySocialUser) = db.readWrite { implicit s =>
        val u = inject[UserRepo].save(User(firstName = "Andrew", lastName = "Conner", username = Username("test"), normalizedUsername = "test"))

        emailAddressRepo.save(UserEmailAddress(userId = u.id.get, address = EmailAddress("andrew@gmail.com")))

        val su = inject[SocialUserInfoRepo].save(SocialUserInfo(
          fullName = "Andrew Conner",
          socialId = SocialId("71105121"),
          networkType = network
        ).withUser(u))
        (u, su)
      }

      val extractedFriends = inject[FacebookSocialGraph].extractFriends(json)

      inject[SocialUserImportFriends].importFriends(mySocialUser, extractedFriends)

      val (user, socialUserInfo) = db.readWrite { implicit c =>
        val user = inject[UserRepo].save(User(firstName = "Greg", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
        val socialUserInfo = inject[SocialUserInfoRepo].save(SocialUserInfo(
          fullName = "Greg Smith",
          socialId = SocialId("gsmith"),
          networkType = SocialNetworks.FACEBOOK
        ).withUser(user))
        emailAddressRepo.save(UserEmailAddress(userId = user.id.get, address = EmailAddress("greg@gmail.com")))
        (user, socialUserInfo)
      }

      (myUser, mySocialUser, user, socialUserInfo, extractedFriends)
    }

    "create connections between friends for social users and kifi users" in {
      withDb(modules: _*) { implicit injector =>

        val (myUser, mySocialUser, user, socialUserInfo, extractedFriends) = setup(db, SocialNetworks.FACEBOOK)

        val connections = inject[UserConnectionCreator].createConnections(socialUserInfo, extractedFriends.map(_.socialId))

        connections.size === 12

        db.readOnlyMaster { implicit s =>
          val fortyTwoConnections = inject[SocialConnectionRepo].getSociallyConnectedUsers(user.id.get)
          val userConnections = inject[UserConnectionRepo].getConnectedUsers(user.id.get)
          val connectionCount = inject[UserConnectionRepo].getConnectionCount(user.id.get)
          fortyTwoConnections === userConnections
          connectionCount === userConnections.size
          connectionCount === 1
        }
        inject[UserConnectionCreator].getConnectionsLastUpdated(user.id.get) must beSome
      }
    }

    def runTest(network: SocialNetworkType, userCreatedAt: DateTime)(matchr: FakeOutbox => MatchResult[Any]) = {
      withDb(modules: _*) { implicit injector =>
        val outbox = inject[FakeOutbox]
        val (myUser, mySocialUser, user, socialUserInfo, extractedFriends) = db.readWrite { implicit rw =>
          val tuple = setup(db, network)
          val user = inject[UserRepo].save(tuple._3.copy(createdAt = userCreatedAt))
          tuple.copy(_3 = user)
          tuple
        }

        val socialConnection = db.readWrite { implicit s =>
          inject[SocialConnectionRepo].save(SocialConnection(socialUser1 = mySocialUser.id.get, socialUser2 = socialUserInfo.id.get))
        }

        val creator = inject[UserConnectionCreator]
        val addedConnections = creator.saveNewSocialUserConnections(socialUserInfo.userId.get)
        Await.ready(creator.notifyAboutNewUserConnections(socialUserInfo.userId.get, Some(network), addedConnections), Duration(5, "seconds"))

        outbox.size === 1
        outbox(0).to === Seq(EmailAddress("andrew@gmail.com"))
        matchr(outbox)
      }
    }

    val now = currentDateTime
    val longTimeAgo = now.minusHours(25)

    "updateUserConnections sends emails to new user (Facebook)" in
      runTest(SocialNetworks.FACEBOOK, now) { outbox =>
        outbox(0).subject === "Your Facebook friend Greg just joined Kifi"
      }

    "updateUserConnections sends emails to old user (Facebook)" in
      runTest(SocialNetworks.FACEBOOK, longTimeAgo) { outbox =>
        outbox(0).subject === "You and Greg Smith are now connected on Kifi!"
      }

    "updateUserConnections sends emails to new user (LinkedIn)" in
      runTest(SocialNetworks.LINKEDIN, now) { outbox =>
        outbox(0).subject === "Your LinkedIn connection Greg just joined Kifi"
      }

    "updateUserConnections sends emails to old user (LinkedIn)" in
      runTest(SocialNetworks.LINKEDIN, longTimeAgo) { outbox =>
        outbox(0).subject === "You and Greg Smith are now connected on Kifi!"
      }

    "disable non existing connections for social users but not kifi users" in {
      withDb(modules: _*) { implicit injector =>

        val json1 = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/facebook_graph_eishay_min.json"), UTF8).mkString)

        val sui1 = inject[Database].readWrite { implicit s =>
          val u1 = inject[UserRepo].save(User(firstName = "Andrew", lastName = "Conner", username = Username("test"), normalizedUsername = "test"))
          val su1 = inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Andrew Conner",
            socialId = SocialId("71105121"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(u1))
          val u2 = inject[UserRepo].save(User(firstName = "Igor", lastName = "Perisic", username = Username("test"), normalizedUsername = "test"))
          inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Igor Perisic",
            socialId = SocialId("28779"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(u2))

          emailAddressRepo.save(UserEmailAddress(userId = u1.id.get, address = EmailAddress("andrew@gmail.com")))
          emailAddressRepo.save(UserEmailAddress(userId = u2.id.get, address = EmailAddress("igor@gmail.com")))

          su1
        }

        val extractedFriends = inject[FacebookSocialGraph].extractFriends(json1)
        inject[SocialUserImportFriends].importFriends(sui1, extractedFriends)

        val (user, socialUserInfo) = inject[Database].readWrite { implicit c =>
          val user = inject[UserRepo].save(User(firstName = "fn1", lastName = "ln1", username = Username("test"), normalizedUsername = "test"))
          val socialUserInfo = inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Bob Smith",
            socialId = SocialId("bsmith"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(user))
          emailAddressRepo.save(UserEmailAddress(userId = user.id.get, address = EmailAddress("bob@gmail.com")))
          (user, socialUserInfo)
        }

        val connections = inject[UserConnectionCreator].createConnections(socialUserInfo,
          extractedFriends.map(_.socialId))

        connections.size === 12

        val json2 = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/facebook_graph_eishay_super_min.json"), UTF8).mkString)

        val extractedFriends2 = inject[FacebookSocialGraph].extractFriends(json2)

        val connectionsAfter = inject[UserConnectionCreator]
          .createConnections(socialUserInfo, extractedFriends2.map(_.socialId))

        inject[Database].readOnlyMaster { implicit s =>
          val sociallyConnectedUsers = inject[SocialConnectionRepo].getSociallyConnectedUsers(user.id.get)
          val userConnections = inject[UserConnectionRepo].getConnectedUsers(user.id.get)
          val connectionCount = inject[UserConnectionRepo].getConnectionCount(user.id.get)
          sociallyConnectedUsers.size === 1
          connectionCount === userConnections.size
          connectionCount === 2
        }
        connectionsAfter.size === 0
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
                primaryEmail = Some(EmailAddress(uFirstName + uLastName + "@kifi.com")), username = Username("test"), normalizedUsername = "test"
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

          val creator = inject[UserConnectionCreator]
          val addedConnections = creator.saveNewSocialUserConnections(user1.id.get)
          Await.ready(creator.notifyAboutNewUserConnections(user1.id.get, None, addedConnections), Duration(5, "seconds"))

          // should be 1 instead of 2 b/c user1 and user2 are "unfriended"
          outbox.size === 1
        }
      }
    }

  }
}
