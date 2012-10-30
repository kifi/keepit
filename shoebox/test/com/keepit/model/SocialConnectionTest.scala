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
        
        CX.withConnection { implicit conn =>
          println("Connections: " + SocialUserInfo.all.size)
        }
        
        val eishaySocialUserInfo = CX.withConnection { implicit conn =>
          SocialUserInfo.get(SocialId("646386018"), SocialNetworks.FACEBOOK).withUser(User(firstName = "Eishay", lastName = "Smith").save).save
        }
        val andrewSocialUserInfo = CX.withConnection { implicit conn =>
          SocialUserInfo.get(SocialId("71105121"), SocialNetworks.FACEBOOK).withUser(User(firstName = "Andrew", lastName = "Conner").save).save
        }

        val eishayJson = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format("facebook_graph_eishay_min.json"))).mkString)
        val andrewJson = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format("facebook_graph_andrew_min.json"))).mkString)

        
        // Create FortyTwo accounts on certain users
        val users = CX.withConnection { implicit conn =>
          val users = User(firstName = "Igor", lastName = "Perisic").save ::
            User(firstName = "Kelvin", lastName = "Jiang").save ::
            User(firstName = "John", lastName = "Cochran").save :: Nil
          
          // These are friends of Eishay
          SocialUserInfo.get(SocialId("28779"), SocialNetworks.FACEBOOK).withUser(users(0)).save
          SocialUserInfo.get(SocialId("102113"), SocialNetworks.FACEBOOK).withUser(users(1)).save

          // Not Eishay's friend
          SocialUserInfo.get(SocialId("113102"), SocialNetworks.FACEBOOK).withUser(users(2)).save
          users
        }
        
        inject[SocialUserCreateConnections].createConnections(eishaySocialUserInfo, Seq(eishayJson))
        inject[SocialUserCreateConnections].createConnections(andrewSocialUserInfo, Seq(andrewJson))
        
        val (eishayFortyTwoConnection, andrewFortyTwoConnection) = CX.withConnection { implicit conn =>
          SocialConnection.all.size === 18
          (SocialConnection.getFortyTwoUserConnections(eishaySocialUserInfo.userId.get),
          SocialConnection.getFortyTwoUserConnections(andrewSocialUserInfo.userId.get))
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
        
        CX.withConnection { implicit conn =>
          println("Connections: " + SocialUserInfo.all.size)
        }
        
        val eishaySocialUserInfo = CX.withConnection { implicit conn =>
          val info = SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("646386018"), networkType = SocialNetworks.FACEBOOK).save
          info.withUser(User(firstName = "Eishay", lastName = "Smith").save).save
        }

        val eishay1Json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format("facebook_graph_eishay_min_page1.json"))).mkString)
        val eishay2Json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format("facebook_graph_eishay_min_page2.json"))).mkString)
        
        // Create FortyTwo accounts on certain users        
        val users = CX.withConnection { implicit conn =>
          val users = User(firstName = "Igor", lastName = "Perisic").save ::
            User(firstName = "Kelvin", lastName = "Jiang").save ::
            User(firstName = "John", lastName = "Cochran").save ::
            User(firstName = "Andrew", lastName = "Conner").save :: Nil
          
          // These are friends of Eishay
          SocialUserInfo.get(SocialId("28779"), SocialNetworks.FACEBOOK).withUser(users(0)).save
          SocialUserInfo.get(SocialId("102113"), SocialNetworks.FACEBOOK).withUser(users(1)).save
          SocialUserInfo.get(SocialId("71105121"), SocialNetworks.FACEBOOK).withUser(users(3)).save
          users
        }
        
        inject[SocialUserCreateConnections].createConnections(eishaySocialUserInfo, Seq(eishay1Json, eishay2Json))
        
        val eishayFortyTwoConnection = CX.withConnection { implicit conn =>
          SocialConnection.all.size === 12
          SocialConnection.getFortyTwoUserConnections(eishaySocialUserInfo.userId.get)
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
        
        CX.withConnection { implicit conn =>
          println("Connections: " + SocialUserInfo.all.size)
        }
        
        val eishaySocialUserInfo = CX.withConnection { implicit conn =>
            SocialUserInfo.get(SocialId("646386018"), SocialNetworks.FACEBOOK).withUser(User(firstName = "Eishay", lastName = "Smith").save).save
        }
        val andrewSocialUserInfo = CX.withConnection { implicit conn =>
            SocialUserInfo.get(SocialId("71105121"), SocialNetworks.FACEBOOK).withUser(User(firstName = "Andrew", lastName = "Conner").save).save
        }

        val eishayJson = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format("facebook_graph_eishay.json"))).mkString)
        val andrewJson = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format("facebook_graph_andrew.json"))).mkString)

        
        // Create FortyTwo accounts on certain users
        val users = scala.collection.mutable.MutableList[User]()
        
        CX.withConnection { implicit conn =>
          users += User(firstName = "Igor", lastName = "Perisic").save
          users += User(firstName = "Kelvin", lastName = "Jiang").save
          users += User(firstName = "John", lastName = "Cochran").save
          
          // These are friends of Eishay
          SocialUserInfo.get(SocialId("28779"), SocialNetworks.FACEBOOK).withUser(users(0)).save
          SocialUserInfo.get(SocialId("102113"), SocialNetworks.FACEBOOK).withUser(users(1)).save

          // Not Eishay's friend
          SocialUserInfo.get(SocialId("113102"), SocialNetworks.FACEBOOK).withUser(users(2)).save
        }
        
        inject[SocialUserCreateConnections].createConnections(eishaySocialUserInfo, Seq(eishayJson))
        inject[SocialUserCreateConnections].createConnections(andrewSocialUserInfo, Seq(andrewJson))
        
        val (eishayFortyTwoConnection, andrewFortyTwoConnection) = CX.withConnection { implicit conn =>
          SocialConnection.all.size === 612
          (SocialConnection.getFortyTwoUserConnections(eishaySocialUserInfo.userId.get),
          SocialConnection.getFortyTwoUserConnections(andrewSocialUserInfo.userId.get))
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
