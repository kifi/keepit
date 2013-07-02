package com.keepit.model

import com.keepit.common.db.slick.Database
import com.keepit.common.social._
import com.keepit.inject._
import com.keepit.test.EmptyApplication
import java.io.File
import org.specs2.mutable._
import play.api.libs.json._
import play.api.test.Helpers._

class SocialConnectionTest extends Specification with ApplicationInjector {


  private def extractFacebookFriendInfo(json: JsValue): Seq[(SocialUserInfo, JsValue)] = {
    inject[FacebookSocialGraph].extractFriends(json)
  }

  private def extractFacebookFriendIds(json: JsValue): Seq[SocialId] = {
    extractFacebookFriendInfo(json).map(_._1.socialId)
  }

  "SocialConnection" should {

    "give Kifi user's connections (min set)" in {
      running(new EmptyApplication().withFakeHttpClient()) {

        def loadJsonImportFriends(filename: String): Unit = {
          val json = Json.parse(io.Source.fromFile(new File("modules/shoebox/test/com/keepit/common/social/data/%s".format(filename))).mkString)
          println(inject[SocialUserImportFriends].importFriends(extractFacebookFriendInfo(json)).size)
        }

        loadJsonImportFriends("facebook_graph_andrew_min.json")
        loadJsonImportFriends("facebook_graph_eishay_min.json")

        val socialRepo = inject[SocialUserInfoRepo]
        val connectionRepo = inject[SocialConnectionRepo]
        val connections = inject[UserConnectionCreator]
        val userRepo = inject[UserRepo]

        val eishaySocialUserInfo = inject[Database].readWrite{ implicit s =>
          socialRepo.save(socialRepo.get(SocialId("646386018"), SocialNetworks.FACEBOOK).withUser(userRepo.save(User(firstName = "Eishay", lastName = "Smith"))))
        }
        val andrewSocialUserInfo = inject[Database].readWrite{ implicit s =>
          socialRepo.save(socialRepo.get(SocialId("71105121"), SocialNetworks.FACEBOOK).withUser(userRepo.save(User(firstName = "Andrew", lastName = "Conner"))))
        }

        val eishayJson = Json.parse(io.Source.fromFile(new File("modules/shoebox/test/com/keepit/common/social/data/%s".format("facebook_graph_eishay_min.json"))).mkString)
        val andrewJson = Json.parse(io.Source.fromFile(new File("modules/shoebox/test/com/keepit/common/social/data/%s".format("facebook_graph_andrew_min.json"))).mkString)


        // Create FortyTwo accounts on certain users
        val users = inject[Database].readWrite{ implicit s =>
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

        connections.createConnections(eishaySocialUserInfo, extractFacebookFriendIds(eishayJson), SocialNetworks.FACEBOOK)
        connections.createConnections(andrewSocialUserInfo, extractFacebookFriendIds(andrewJson), SocialNetworks.FACEBOOK)

        val (eishayFortyTwoConnection, andrewFortyTwoConnection) = inject[Database].readOnly{ implicit s =>
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
      running(new EmptyApplication().withFakeHttpClient()) {

        def loadJsonImportFriends(filename: String): Unit = {
          val json = Json.parse(io.Source.fromFile(new File("modules/shoebox/test/com/keepit/common/social/data/%s".format(filename))).mkString)
          println(inject[SocialUserImportFriends].importFriends(extractFacebookFriendInfo(json)).size)
        }

        loadJsonImportFriends("facebook_graph_andrew_min.json")
        loadJsonImportFriends("facebook_graph_eishay_min.json")

        inject[Database].readOnly{ implicit s =>
          println("Connections: " + inject[SocialUserInfoRepo].all.size)
        }

        val userRepo = inject[UserRepo]
        val socialRepo = inject[SocialUserInfoRepo]
        val eishaySocialUserInfo = inject[Database].readWrite{ implicit s =>
          socialRepo.save(socialRepo.get(SocialId("646386018"), SocialNetworks.FACEBOOK).withUser(userRepo.save(User(firstName = "Eishay", lastName = "Smith"))))
        }
        val andrewSocialUserInfo = inject[Database].readWrite{ implicit s =>
          socialRepo.save(socialRepo.get(SocialId("71105121"), SocialNetworks.FACEBOOK).withUser(userRepo.save(User(firstName = "Andrew", lastName = "Conner"))))
        }

        val eishayJson = Json.parse(io.Source.fromFile(new File("modules/shoebox/test/com/keepit/common/social/data/%s".format("facebook_graph_eishay_min.json"))).mkString)
        val andrewJson = Json.parse(io.Source.fromFile(new File("modules/shoebox/test/com/keepit/common/social/data/%s".format("facebook_graph_andrew_min.json"))).mkString)


        // Create FortyTwo accounts on certain users
        val users = inject[Database].readWrite{ implicit s =>
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

        inject[UserConnectionCreator].createConnections(eishaySocialUserInfo,
          extractFacebookFriendIds(eishayJson), SocialNetworks.FACEBOOK)
        inject[UserConnectionCreator].createConnections(andrewSocialUserInfo,
          extractFacebookFriendIds(andrewJson), SocialNetworks.FACEBOOK)

        val connectionRepo = inject[SocialConnectionRepo]

        val (eishayFortyTwoConnection, andrewFortyTwoConnection) = inject[Database].readOnly{ implicit s =>
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
      running(new EmptyApplication().withFakeHttpClient()) {

        def loadJsonImportFriends(filenames: Seq[String]): Unit = {
          val jsons = filenames map { filename =>
            Json.parse(io.Source.fromFile(new File("modules/shoebox/test/com/keepit/common/social/data/%s".format(filename))).mkString)
          }
          println(inject[SocialUserImportFriends].importFriends(jsons flatMap extractFacebookFriendInfo).size)
        }

        loadJsonImportFriends(Seq("facebook_graph_eishay_min_page1.json", "facebook_graph_eishay_min_page2.json"))

        inject[Database].readOnly{ implicit s =>
          println("Connections: " + inject[SocialUserInfoRepo].all.size)
        }

        val socialRepo = inject[SocialUserInfoRepo]
        val connectionRepo = inject[SocialConnectionRepo]
        val connections = inject[UserConnectionCreator]
        val userRepo = inject[UserRepo]
        val eishaySocialUserInfo = inject[Database].readWrite{ implicit s =>
          val info = socialRepo.save(SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("646386018"), networkType = SocialNetworks.FACEBOOK))
          socialRepo.save(info.withUser(userRepo.save(User(firstName = "Eishay", lastName = "Smith"))))
        }

        val eishay1Json = Json.parse(io.Source.fromFile(new File("modules/shoebox/test/com/keepit/common/social/data/%s".format("facebook_graph_eishay_min_page1.json"))).mkString)
        val eishay2Json = Json.parse(io.Source.fromFile(new File("modules/shoebox/test/com/keepit/common/social/data/%s".format("facebook_graph_eishay_min_page2.json"))).mkString)

        // Create FortyTwo accounts on certain users
        val users = inject[Database].readWrite{ implicit s =>
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

        inject[UserConnectionCreator].createConnections(eishaySocialUserInfo,
          Seq(eishay1Json, eishay2Json) flatMap extractFacebookFriendIds, SocialNetworks.FACEBOOK)

        val eishayFortyTwoConnection = inject[Database].readOnly{ implicit s =>
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

  }

}
