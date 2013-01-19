package com.keepit.model

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import com.keepit.common.db.CX
import com.keepit.common.db.CX._
import com.keepit.test.EmptyApplication
import com.keepit.common.social.SocialId
import com.keepit.common.social.SocialNetworks
import com.keepit.common.social.SocialUserCreateConnections
import com.keepit.inject._
import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._
import play.api.libs.json._
import java.io.File
import com.keepit.common.social.SocialUserImportFriends
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBConnection

@RunWith(classOf[JUnitRunner])
class SocialConnectionTest extends SpecificationWithJUnit {


  "SocialConnection" should {

    "give Kifi user's connections (min set)" in {
      running(new EmptyApplication().withFakeStore) {

        def loadJsonImportFriends(filename: String): Unit = {
          val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format(filename))).mkString)
          println(inject[SocialUserImportFriends].importFriends(Seq(json)).size)
        }

        loadJsonImportFriends("facebook_graph_andrew_min.json")
        loadJsonImportFriends("facebook_graph_eishay_min.json")

        val socialRepo = inject[SocialUserInfoRepo]
        val connectionRepo = inject[SocialConnectionRepo]
        val connections = inject[SocialUserCreateConnections]
        val userRepo = inject[UserRepo]

        val eishaySocialUserInfo = inject[DBConnection].readWrite{ implicit s =>
          socialRepo.save(socialRepo.get(SocialId("646386018"), SocialNetworks.FACEBOOK).withUser(userRepo.save(User(firstName = "Eishay", lastName = "Smith"))))
        }
        val andrewSocialUserInfo = inject[DBConnection].readWrite{ implicit s =>
          socialRepo.save(socialRepo.get(SocialId("71105121"), SocialNetworks.FACEBOOK).withUser(userRepo.save(User(firstName = "Andrew", lastName = "Conner"))))
        }

        val eishayJson = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format("facebook_graph_eishay_min.json"))).mkString)
        val andrewJson = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format("facebook_graph_andrew_min.json"))).mkString)


        // Create FortyTwo accounts on certain users
        val users = inject[DBConnection].readWrite{ implicit s =>
          val users = userRepo.save(User(firstName = "Igor", lastName = "Perisic")) ::
            userRepo.save(User(firstName = "Kelvin", lastName = "Jiang")) ::
            userRepo.save(User(firstName = "John", lastName = "Cochran")) :: Nil

          // These are friends of Eishay
          socialRepo.save(socialRepo.get(SocialId("28779"), SocialNetworks.FACEBOOK).withUser(users(0)))
          socialRepo.save(socialRepo.get(SocialId("102113"), SocialNetworks.FACEBOOK).withUser(users(1)))

          // Not Eishay's friend
          socialRepo.save(socialRepo.get(SocialId("113102"), SocialNetworks.FACEBOOK).withUser(users(2)))
          users
        }

        connections.createConnections(eishaySocialUserInfo, Seq(eishayJson))
        connections.createConnections(andrewSocialUserInfo, Seq(andrewJson))

        val (eishayFortyTwoConnection, andrewFortyTwoConnection) = inject[DBConnection].readOnly{ implicit s =>
          connectionRepo.count === 18
          (connectionRepo.getFortyTwoUserConnections(eishaySocialUserInfo.userId.get),
           connectionRepo.getFortyTwoUserConnections(andrewSocialUserInfo.userId.get))
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
      running(new EmptyApplication().withFakeStore) {

        def loadJsonImportFriends(filename: String): Unit = {
          val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format(filename))).mkString)
          println(inject[SocialUserImportFriends].importFriends(Seq(json)).size)
        }

        loadJsonImportFriends("facebook_graph_andrew_min.json")
        loadJsonImportFriends("facebook_graph_eishay_min.json")

        inject[DBConnection].readOnly{ implicit s =>
          println("Connections: " + inject[SocialUserInfoRepo].all.size)
        }

        val userRepo = inject[UserRepo]
        val socialRepo = inject[SocialUserInfoRepo]
        val eishaySocialUserInfo = inject[DBConnection].readWrite{ implicit s =>
          socialRepo.save(socialRepo.get(SocialId("646386018"), SocialNetworks.FACEBOOK).withUser(userRepo.save(User(firstName = "Eishay", lastName = "Smith"))))
        }
        val andrewSocialUserInfo = inject[DBConnection].readWrite{ implicit s =>
          socialRepo.save(socialRepo.get(SocialId("71105121"), SocialNetworks.FACEBOOK).withUser(userRepo.save(User(firstName = "Andrew", lastName = "Conner"))))
        }

        val eishayJson = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format("facebook_graph_eishay_min.json"))).mkString)
        val andrewJson = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format("facebook_graph_andrew_min.json"))).mkString)


        // Create FortyTwo accounts on certain users
        val users = inject[DBConnection].readWrite{ implicit s =>
          val users = userRepo.save(User(firstName = "Igor", lastName = "Perisic")) ::
            userRepo.save(User(firstName = "Kelvin", lastName = "Jiang")) ::
            userRepo.save(User(firstName = "John", lastName = "Cochran")) :: Nil

          // These are friends of Eishay
          socialRepo.save(socialRepo.get(SocialId("28779"), SocialNetworks.FACEBOOK).withUser(users(0)))
          socialRepo.save(socialRepo.get(SocialId("102113"), SocialNetworks.FACEBOOK).withUser(users(1)))

          // Not Eishay's friend
          socialRepo.save(socialRepo.get(SocialId("113102"), SocialNetworks.FACEBOOK).withUser(users(2)))
          users
        }

        inject[SocialUserCreateConnections].createConnections(eishaySocialUserInfo, Seq(eishayJson))
        inject[SocialUserCreateConnections].createConnections(andrewSocialUserInfo, Seq(andrewJson))

        val connectionRepo = inject[SocialConnectionRepo]

        val (eishayFortyTwoConnection, andrewFortyTwoConnection) = inject[DBConnection].readOnly{ implicit s =>
          connectionRepo.all.size === 18
          (connectionRepo.getFortyTwoUserConnections(eishaySocialUserInfo.userId.get),
           connectionRepo.getFortyTwoUserConnections(andrewSocialUserInfo.userId.get))
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
      running(new EmptyApplication().withFakeStore) {

        def loadJsonImportFriends(filenames: Seq[String]): Unit = {
          val jsons = filenames map { filename =>
            Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format(filename))).mkString)
          }
          println(inject[SocialUserImportFriends].importFriends(jsons).size)
        }

        loadJsonImportFriends(Seq("facebook_graph_eishay_min_page1.json", "facebook_graph_eishay_min_page2.json"))

        inject[DBConnection].readOnly{ implicit s =>
          println("Connections: " + inject[SocialUserInfoRepo].all.size)
        }

        val socialRepo = inject[SocialUserInfoRepo]
        val connectionRepo = inject[SocialConnectionRepo]
        val connections = inject[SocialUserCreateConnections]
        val userRepo = inject[UserRepo]
        val eishaySocialUserInfo = inject[DBConnection].readWrite{ implicit s =>
          val info = socialRepo.save(SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("646386018"), networkType = SocialNetworks.FACEBOOK))
          socialRepo.save(info.withUser(userRepo.save(User(firstName = "Eishay", lastName = "Smith"))))
        }

        val eishay1Json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format("facebook_graph_eishay_min_page1.json"))).mkString)
        val eishay2Json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format("facebook_graph_eishay_min_page2.json"))).mkString)

        // Create FortyTwo accounts on certain users
        val users = inject[DBConnection].readWrite{ implicit s =>
          val users = userRepo.save(User(firstName = "Igor", lastName = "Perisic")) ::
            userRepo.save(User(firstName = "Kelvin", lastName = "Jiang")) ::
            userRepo.save(User(firstName = "John", lastName = "Cochran")) ::
            userRepo.save(User(firstName = "Andrew", lastName = "Conner")) :: Nil

          // These are friends of Eishay
          socialRepo.save(socialRepo.get(SocialId("28779"), SocialNetworks.FACEBOOK).withUser(users(0)))
          socialRepo.save(socialRepo.get(SocialId("102113"), SocialNetworks.FACEBOOK).withUser(users(1)))
          socialRepo.save(socialRepo.get(SocialId("71105121"), SocialNetworks.FACEBOOK).withUser(users(3)))
          users
        }

        inject[SocialUserCreateConnections].createConnections(eishaySocialUserInfo, Seq(eishay1Json, eishay2Json))

        val eishayFortyTwoConnection = inject[DBConnection].readOnly{ implicit s =>
          connectionRepo.all.size === 12
          connectionRepo.getFortyTwoUserConnections(eishaySocialUserInfo.userId.get)
        }

        eishayFortyTwoConnection.size === 3
        eishayFortyTwoConnection.contains(users(0).id.get) === true
        eishayFortyTwoConnection.contains(users(1).id.get) === true
        eishayFortyTwoConnection.contains(users(2).id.get) === false
        eishayFortyTwoConnection.contains(users(3).id.get) === true

      }
    }

    "give Kifi user's connections (full set)" in {
      running(new EmptyApplication().withFakeStore) {

        def loadJsonImportFriends(filename: String): Unit = {
          val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format(filename))).mkString)
          println(inject[SocialUserImportFriends].importFriends(Seq(json)).size)
        }

        loadJsonImportFriends("facebook_graph_andrew.json")
        loadJsonImportFriends("facebook_graph_eishay.json")

        val socialRepo = inject[SocialUserInfoRepo]
        val connectionRepo = inject[SocialConnectionRepo]
        val connections = inject[SocialUserCreateConnections]
        val userRepo = inject[UserRepo]

        val eishaySocialUserInfo = inject[DBConnection].readWrite{ implicit s =>
            socialRepo.save(socialRepo.get(SocialId("646386018"), SocialNetworks.FACEBOOK).withUser(userRepo.save(User(firstName = "Eishay", lastName = "Smith"))))
        }
        val andrewSocialUserInfo = inject[DBConnection].readWrite{ implicit s =>
            socialRepo.save(socialRepo.get(SocialId("71105121"), SocialNetworks.FACEBOOK).withUser(userRepo.save(User(firstName = "Andrew", lastName = "Conner"))))
        }

        val eishayJson = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format("facebook_graph_eishay.json"))).mkString)
        val andrewJson = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format("facebook_graph_andrew.json"))).mkString)


        // Create FortyTwo accounts on certain users
        val users = scala.collection.mutable.MutableList[User]()

        inject[DBConnection].readWrite{ implicit s =>
          users += userRepo.save(User(firstName = "Igor", lastName = "Perisic"))
          users += userRepo.save(User(firstName = "Kelvin", lastName = "Jiang"))
          users += userRepo.save(User(firstName = "John", lastName = "Cochran"))

          // These are friends of Eishay
          socialRepo.save(socialRepo.get(SocialId("28779"), SocialNetworks.FACEBOOK).withUser(users(0)))
          socialRepo.save(socialRepo.get(SocialId("102113"), SocialNetworks.FACEBOOK).withUser(users(1)))

          // Not Eishay's friend
          socialRepo.save(socialRepo.get(SocialId("113102"), SocialNetworks.FACEBOOK).withUser(users(2)))
        }

        inject[SocialUserCreateConnections].createConnections(eishaySocialUserInfo, Seq(eishayJson))
        inject[SocialUserCreateConnections].createConnections(andrewSocialUserInfo, Seq(andrewJson))

        val (eishayFortyTwoConnection, andrewFortyTwoConnection) = inject[DBConnection].readOnly{ implicit s =>
          connectionRepo.all.size === 612
          (connectionRepo.getFortyTwoUserConnections(eishaySocialUserInfo.userId.get),
           connectionRepo.getFortyTwoUserConnections(andrewSocialUserInfo.userId.get))
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
  }

}
