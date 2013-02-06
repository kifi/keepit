package com.keepit.common.social

import java.io.File

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import com.keepit.common.db.CX
import com.keepit.common.db.slick.DBConnection
import com.keepit.inject._
import com.keepit.model.{UserRepo, SocialUserInfoRepo, SocialUserInfo, User}
import com.keepit.test.EmptyApplication

import play.api.Play.current
import play.api.libs.json.Json
import play.api.test.Helpers._

@RunWith(classOf[JUnitRunner])
class SocialUserCreateConnectionsTest extends SpecificationWithJUnit {


  "SocialUserCreateConnections" should {
    "create connections between friends" in {
      running(new EmptyApplication().withFakeStore) {

        /*
         * grab json
         * import friends (create SocialUserInfo records)
         * using json and one socialuserinfo, create connections
         *
         */
        val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/facebook_graph_eishay.json")).mkString)

        val socialUserInfo = inject[DBConnection].readWrite { implicit c =>
          val user = inject[UserRepo].save(User(firstName = "fn1", lastName = "ln1"))
          inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Bob Smith",
            socialId = SocialId("bsmith"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(user))
        }

        val connections = inject[SocialUserCreateConnections].createConnections(socialUserInfo, Seq(json))

        connections.size === 199
      }
    }
    
    "disable non existing connecions" in {
      running(new EmptyApplication().withFakeStore) {

        val json1 = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/facebook_graph_eishay.json")).mkString)

        inject[SocialUserImportFriends].importFriends(Seq(json1))

        val socialUserInfo = inject[DBConnection].readWrite { implicit c =>
          val user = inject[UserRepo].save(User(firstName = "fn1", lastName = "ln1"))
          inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Bob Smith",
            socialId = SocialId("bsmith"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(user))
        }

        val connections = inject[SocialUserCreateConnections].createConnections(socialUserInfo, Seq(json1))

        connections.size === 199
        
        val json2 = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/facebook_graph_eishay_min.json")).mkString)

        val connectionsAfter = inject[SocialUserCreateConnections].createConnections(socialUserInfo, Seq(json2))
        connectionsAfter.size === 12
      }
    }
    
  }


}
