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
    "give Kifi user's connections" in {
      running(new EmptyApplication().withFakeStore) {
        
        def loadJson(filename: String): Unit = {
          val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format(filename))).mkString)
          inject[SocialUserImportFriends].importFriends(json)
        }
        
        loadJson("facebook_graph_andrew.json")
        loadJson("facebook_graph_eishay.json")
        
        val socialUserInfo = CX.withConnection { implicit conn =>
          // Eishay's Facebook Account
          //SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("646386018"), networkType = SocialNetworks.FACEBOOK).save
            SocialUserInfo.get(SocialId("646386018"), SocialNetworks.FACEBOOK).withUser(User(firstName = "Eishay", lastName = "Smith").save).save
        }

        val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format("facebook_graph_eishay.json"))).mkString)
        
        // Create FortyTwo accounts on certain users
        val users = scala.collection.mutable.MutableList[User]()
        
        CX.withConnection { implicit conn =>
          users += User(firstName = "Andrew", lastName = "Conner").save
          users += User(firstName = "Igor", lastName = "Perisic").save
          users += User(firstName = "Kelvin", lastName = "Jiang").save
          users += User(firstName = "John", lastName = "Cochran").save
          
          // These are friends of Eishay
          SocialUserInfo.get(SocialId("71105121"), SocialNetworks.FACEBOOK).withUser(users(0)).save
          SocialUserInfo.get(SocialId("28779"), SocialNetworks.FACEBOOK).withUser(users(1)).save
          SocialUserInfo.get(SocialId("102113"), SocialNetworks.FACEBOOK).withUser(users(2)).save

          // Not Eishay's friend
          SocialUserInfo.get(SocialId("113102"), SocialNetworks.FACEBOOK).withUser(users(3)).save
        }
        
        inject[SocialUserCreateConnections].createConnections(socialUserInfo, json)
        
        val connections = CX.withConnection { implicit conn =>
          SocialConnection.getFortyTwoUserConnections(socialUserInfo.userId.get)
        }
        
        connections.size === 3
        connections.contains(users(0).id.get) === true
        connections.contains(users(1).id.get) === true
        connections.contains(users(3).id.get) === false

      }
    }
  }
  
}
