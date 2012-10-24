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
    "give user's connections" in {
      running(new EmptyApplication().withFakeStore) {
        
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
        
        inject[SocialUserCreateConnections].createConnections(socialUserInfo, json)
        
        val connections = CX.withConnection { implicit conn =>
          SocialConnection.getByUser(socialUserInfo.userId.get)
        }
        
        connections === Set(2,3,4,5).map(i=> Id[User](i))
      }
    }
  }
  
}
