package com.keepit.model

import com.keepit.common.strings.UTF8
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.{ FakeExecutionContextModule, ExecutionContextModule }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.social._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.test.ShoeboxTestInjector
import java.io.File
import org.specs2.mutable._
import play.api.libs.json._
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.google.inject.Injector
import com.keepit.shoebox.FakeShoeboxServiceClientModule
import com.keepit.social.{ SocialNetworks, SocialId }
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }

class SocialConnectionTest extends Specification with ShoeboxTestInjector {

  val socialConnectionTestModules = Seq(
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

  private def extractFacebookFriendInfo(json: JsValue)(implicit injector: Injector): Seq[SocialUserInfo] = {
    inject[FacebookSocialGraph].extractFriends(json)
  }

  private def extractFacebookFriendIds(json: JsValue)(implicit injector: Injector): Seq[SocialId] = {
    extractFacebookFriendInfo(json).map(_.socialId)
  }

  def createEmailForUsers(users: List[User])(implicit injector: Injector, rw: RWSession) =
    users.foreach { user: User =>
      inject[UserEmailAddressRepo].save(UserEmailAddress(userId = user.id.get, address = EmailAddress(s"${user.firstName}@gmail.com")))
    }

  "SocialConnection" should {

    "with username" in {
      withDb(socialConnectionTestModules: _*) { implicit injector =>
        val socialUser = inject[Database].readWrite { implicit s =>
          val u = inject[UserRepo].save(User(firstName = "Andrew", lastName = "Conner", username = Username("test"), normalizedUsername = "test"))
          val su = inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Andrew Conner",
            socialId = SocialId("71105121"),
            networkType = SocialNetworks.TWITTER,
            username = Some("andrew")
          ).withUser(u))
          su
        }
        inject[Database].readOnlyMaster { implicit s =>
          inject[SocialUserInfoRepo].get(socialUser.id.get) === socialUser
        }
      }
    }

    "give Kifi user's connections (min set)" in {
      withDb(socialConnectionTestModules: _*) { implicit injector =>

        val socialUser = inject[Database].readWrite { implicit s =>
          val u = inject[UserRepo].save(User(firstName = "Andrew", lastName = "Conner", username = Username("test"), normalizedUsername = "test"))
          val su = inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Andrew Conner",
            socialId = SocialId("71105121"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(u))
          su
        }

        def loadJsonImportFriends(filename: String): Unit = {
          val json = Json.parse(io.Source.fromFile(new File(s"test/com/keepit/common/social/data/$filename"), UTF8).mkString)
          inject[SocialUserImportFriends].importFriends(socialUser, extractFacebookFriendInfo(json)).size
        }

        loadJsonImportFriends("facebook_graph_andrew_min.json")
        loadJsonImportFriends("facebook_graph_eishay_min.json")

        val socialRepo = inject[SocialUserInfoRepo]
        val connectionRepo = inject[SocialConnectionRepo]
        val connections = inject[UserConnectionCreator]
        val userRepo = inject[UserRepo]
        val emailRepo = inject[UserEmailAddressRepo]

        val (eishaySocialUserInfo, andrewSocialUserInfo) = inject[Database].readWrite { implicit s =>
          (
            socialRepo.save(socialRepo.get(SocialId("646386018"), SocialNetworks.FACEBOOK).withUser(userRepo.save(User(firstName = "Eishay", lastName = "Smith", username = Username("test"), normalizedUsername = "test")))),
            socialRepo.save(socialRepo.get(SocialId("71105121"), SocialNetworks.FACEBOOK).withUser(userRepo.save(User(firstName = "Andrew", lastName = "Conner", username = Username("test2"), normalizedUsername = "test2"))))
          )
        }

        val eishayJson = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/%s".format("facebook_graph_eishay_min.json")), UTF8).mkString)
        val andrewJson = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/%s".format("facebook_graph_andrew_min.json")), UTF8).mkString)

        // Create FortyTwo accounts on certain users
        val users = inject[Database].readWrite { implicit s =>

          // existing users must have emails
          emailRepo.save(UserEmailAddress(userId = eishaySocialUserInfo.userId.get, address = EmailAddress("eishay@gmail.com")))
          emailRepo.save(UserEmailAddress(userId = andrewSocialUserInfo.userId.get, address = EmailAddress("andrew@gmail.com")))

          val users = userRepo.save(User(firstName = "Igor", lastName = "Perisic", username = Username("test"), normalizedUsername = "test")) ::
            userRepo.save(User(firstName = "Kelvin", lastName = "Jiang", username = Username("test2"), normalizedUsername = "test2")) ::
            userRepo.save(User(firstName = "John", lastName = "Cochran", username = Username("test3"), normalizedUsername = "test3")) :: Nil

          createEmailForUsers(users)

          // These are friends of Eishay
          socialRepo.save(socialRepo.get(SocialId("28779"), SocialNetworks.FACEBOOK).withUser(users(0)))
          socialRepo.save(socialRepo.get(SocialId("102113"), SocialNetworks.FACEBOOK).withUser(users(1)))

          // Not Eishay's friend
          socialRepo.save(socialRepo.get(SocialId("113102"), SocialNetworks.FACEBOOK).withUser(users(2)))
          users
        }

        connections.createConnections(eishaySocialUserInfo, extractFacebookFriendIds(eishayJson))
        connections.createConnections(andrewSocialUserInfo, extractFacebookFriendIds(andrewJson))

        val (eishayFortyTwoConnection, andrewFortyTwoConnection) = inject[Database].readOnlyMaster { implicit s =>
          connectionRepo.count === 18
          (connectionRepo.getSociallyConnectedUsers(eishaySocialUserInfo.userId.get),
            connectionRepo.getSociallyConnectedUsers(andrewSocialUserInfo.userId.get))
        }

        eishayFortyTwoConnection.size === 3
        eishayFortyTwoConnection.contains(users(0).id.get) === true
        eishayFortyTwoConnection.contains(users(1).id.get) === true
        eishayFortyTwoConnection.contains(users(2).id.get) === false

        andrewFortyTwoConnection.size === 2
        andrewFortyTwoConnection.contains(users(0).id.get) === false
        andrewFortyTwoConnection.contains(users(1).id.get) === false
        andrewFortyTwoConnection.contains(users(2).id.get) === true

      }
    }
    "give Kifi user's connections (min set) w/o non active connections" in {
      withDb(socialConnectionTestModules: _*) { implicit injector =>

        val (socialUser, andrewUser: User) = inject[Database].readWrite { implicit s =>
          val u: User = inject[UserRepo].save(User(firstName = "Andrew", lastName = "Conner", username = Username("test"), normalizedUsername = "test"))
          val su = inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Andrew Conner",
            socialId = SocialId("71105121"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(u))

          (su, u)
        }

        def loadJsonImportFriends(filename: String): Unit = {
          val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/%s".format(filename)), UTF8).mkString)
          inject[SocialUserImportFriends].importFriends(socialUser, extractFacebookFriendInfo(json)).size
        }

        loadJsonImportFriends("facebook_graph_andrew_min.json")
        loadJsonImportFriends("facebook_graph_eishay_min.json")

        val userRepo = inject[UserRepo]
        val socialRepo = inject[SocialUserInfoRepo]
        val (eishaySocialUserInfo, eishayUser) = inject[Database].readWrite { implicit s =>
          var user = userRepo.save(User(firstName = "Eishay", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
          (socialRepo.save(socialRepo.get(SocialId("646386018"), SocialNetworks.FACEBOOK).withUser(user)), user)
        }
        val (andrewSocialUserInfo, andrewUser2) = inject[Database].readWrite { implicit s =>
          var user = userRepo.save(User(firstName = "Andrew", lastName = "Conner", username = Username("test"), normalizedUsername = "test"))
          (socialRepo.save(socialRepo.get(SocialId("71105121"), SocialNetworks.FACEBOOK).withUser(user)), user)
        }

        val eishayJson = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/%s".format("facebook_graph_eishay_min.json")), UTF8).mkString)
        val andrewJson = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/%s".format("facebook_graph_andrew_min.json")), UTF8).mkString)

        // Create FortyTwo accounts on certain users
        val users = inject[Database].readWrite { implicit s =>
          val users = userRepo.save(User(firstName = "Igor", lastName = "Perisic", username = Username("test"), normalizedUsername = "test")) ::
            userRepo.save(User(firstName = "Kelvin", lastName = "Jiang", username = Username("test"), normalizedUsername = "test")) ::
            userRepo.save(User(firstName = "John", lastName = "Cochran", username = Username("test"), normalizedUsername = "test")) :: Nil

          createEmailForUsers(andrewUser :: andrewUser2 :: eishayUser :: users)

          // These are friends of Eishay
          socialRepo.save(socialRepo.get(SocialId("28779"), SocialNetworks.FACEBOOK).withUser(users(0)))
          socialRepo.save(socialRepo.get(SocialId("102113"), SocialNetworks.FACEBOOK).withUser(users(1)))

          // Not Eishay's friend
          socialRepo.save(socialRepo.get(SocialId("113102"), SocialNetworks.FACEBOOK).withUser(users(2)))
          users
        }

        inject[UserConnectionCreator].createConnections(eishaySocialUserInfo,
          extractFacebookFriendIds(eishayJson))
        inject[UserConnectionCreator].createConnections(andrewSocialUserInfo,
          extractFacebookFriendIds(andrewJson))

        val connectionRepo = inject[SocialConnectionRepo]

        val (eishayFortyTwoConnection, andrewFortyTwoConnection) = inject[Database].readOnlyMaster { implicit s =>
          connectionRepo.all.size === 18
          (connectionRepo.getSociallyConnectedUsers(eishaySocialUserInfo.userId.get),
            connectionRepo.getSociallyConnectedUsers(andrewSocialUserInfo.userId.get))
        }

        eishayFortyTwoConnection.size === 3
        eishayFortyTwoConnection.contains(users(0).id.get) === true
        eishayFortyTwoConnection.contains(users(1).id.get) === true
        eishayFortyTwoConnection.contains(users(2).id.get) === false

        andrewFortyTwoConnection.size === 2
        andrewFortyTwoConnection.contains(users(0).id.get) === false
        andrewFortyTwoConnection.contains(users(1).id.get) === false
        andrewFortyTwoConnection.contains(users(2).id.get) === true

      }
    }

    "give Kifi user's connections (min set) with pagination" in {
      withDb(socialConnectionTestModules: _*) { implicit injector =>

        val (socialUser, andrewUser) = inject[Database].readWrite { implicit s =>
          val u = inject[UserRepo].save(User(firstName = "Andrew", lastName = "Conner", username = Username("test"), normalizedUsername = "test"))
          val su = inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Andrew Conner",
            socialId = SocialId("71105121"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(u))
          (su, u)
        }

        def loadJsonImportFriends(filenames: Seq[String]): Unit = {
          val jsons = filenames map { filename =>
            Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/%s".format(filename)), UTF8).mkString)
          }
          inject[SocialUserImportFriends].importFriends(socialUser, jsons flatMap extractFacebookFriendInfo).size
        }

        loadJsonImportFriends(Seq("facebook_graph_eishay_min_page1.json", "facebook_graph_eishay_min_page2.json"))

        val socialRepo = inject[SocialUserInfoRepo]
        val connectionRepo = inject[SocialConnectionRepo]
        val connections = inject[UserConnectionCreator]
        val userRepo = inject[UserRepo]
        val (eishaySocialUserInfo, eishayUser) = inject[Database].readWrite { implicit s =>
          val info = socialRepo.save(SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("646386018"), networkType = SocialNetworks.FACEBOOK))
          var user = userRepo.save(User(firstName = "Eishay", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
          (socialRepo.save(info.withUser(user)), user)
        }

        val eishay1Json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/%s".format("facebook_graph_eishay_min_page1.json")), UTF8).mkString)
        val eishay2Json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/%s".format("facebook_graph_eishay_min_page2.json")), UTF8).mkString)

        // Create FortyTwo accounts on certain users
        val users = inject[Database].readWrite { implicit s =>
          val users = userRepo.save(User(firstName = "Igor", lastName = "Perisic", username = Username("test"), normalizedUsername = "test")) ::
            userRepo.save(User(firstName = "Kelvin", lastName = "Jiang", username = Username("test2"), normalizedUsername = "test2")) ::
            userRepo.save(User(firstName = "John", lastName = "Cochran", username = Username("test3"), normalizedUsername = "test3")) ::
            userRepo.save(User(firstName = "Andrew", lastName = "Conner", username = Username("test4"), normalizedUsername = "test4")) :: Nil

          createEmailForUsers(andrewUser :: eishayUser :: users)

          // These are friends of Eishay
          socialRepo.save(socialRepo.get(SocialId("28779"), SocialNetworks.FACEBOOK).withUser(users(0)))
          socialRepo.save(socialRepo.get(SocialId("102113"), SocialNetworks.FACEBOOK).withUser(users(1)))
          socialRepo.save(socialRepo.get(SocialId("71105121"), SocialNetworks.FACEBOOK).withUser(users(3)))
          users
        }

        inject[UserConnectionCreator].createConnections(eishaySocialUserInfo,
          Seq(eishay1Json, eishay2Json) flatMap extractFacebookFriendIds)

        val eishayFortyTwoConnection = inject[Database].readOnlyMaster { implicit s =>
          connectionRepo.all.size === 12
          connectionRepo.getSociallyConnectedUsers(eishaySocialUserInfo.userId.get)
        }

        eishayFortyTwoConnection.size === 3
        eishayFortyTwoConnection.contains(users(0).id.get) === true
        eishayFortyTwoConnection.contains(users(1).id.get) === true
        eishayFortyTwoConnection.contains(users(2).id.get) === false
        eishayFortyTwoConnection.contains(users(3).id.get) === true

      }
    }

  }

}
