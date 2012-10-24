package com.keepit.common.social

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import ru.circumflex.orm._
import java.util.concurrent.TimeUnit
import com.keepit.inject._
import com.keepit.controllers._
import com.keepit.common.db.Id
import com.keepit.common.db.CX
import com.keepit.common.db.CX._
import com.keepit.test.EmptyApplication
import com.keepit.common.net.HttpClientImpl
import com.keepit.model.User
import securesocial.core.{SocialUser, UserId, AuthenticationMethod, OAuth2Info}
import com.keepit.common.net.FakeHttpClient
import com.keepit.model.SocialUserInfo
import play.api.Play
import java.net.URL
import java.io.File
import play.core.TestApplication
import scala.collection.mutable.Map

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
        
        val result = inject[SocialUserImportFriends].importFriends(json)
        
        val socialUserInfo = CX.withConnection { implicit conn =>
          SocialUserInfo(fullName = "Bob Smith", socialId = SocialId("bsmith"), networkType = SocialNetworks.FACEBOOK).withUser(User(firstName = "fn1", lastName = "ln1").save).save
        }
        
        CX.withConnection { implicit conn =>
          SocialUserInfo.get(result(1).socialUserInfoId.get).withUser(User(firstName = "fn1", lastName = "ln1").save).save
          SocialUserInfo.get(result(2).socialUserInfoId.get).withUser(User(firstName = "fn1", lastName = "ln1").save).save
          SocialUserInfo.get(result(3).socialUserInfoId.get).withUser(User(firstName = "fn1", lastName = "ln1").save).save
          SocialUserInfo.get(result(4).socialUserInfoId.get).withUser(User(firstName = "fn1", lastName = "ln1").save).save
        }
        
        val connections = inject[SocialUserCreateConnections].createConnections(socialUserInfo, json)

        connections.size === 4      
      }
    }
  }

  
}
