package com.keepit.common.social

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import java.util.concurrent.TimeUnit
import com.keepit.inject._
import com.keepit.controllers._
import com.keepit.common.db.Id
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
class SocialUserImportFriendsTest extends SpecificationWithJUnit {

  "SocialUserImportFriends" should {
    "import friends" in {
      running(new EmptyApplication().withFakeStore) {
        val graphs = List(
            ("facebook_graph_andrew.json", 414, "Kristen Lynch"),
            ("facebook_graph_eishay.json", 198, "Igor Perisic")
        )
        graphs map { case (filename, numOfFriends, firstFriend) => testFacebookGraph(filename, numOfFriends, firstFriend) }

      }
    }
  }

  def testFacebookGraph(jsonFilename: String, numOfFriends: Int, firstFriend: String) = {
    val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/%s".format(jsonFilename))).mkString)
    val rawFriends = inject[SocialUserImportFriends].importFriends(Seq(json))
    val store = inject[SocialUserRawInfoStore].asInstanceOf[Map[Id[SocialUserInfo], SocialUserRawInfo]]
    store.size === numOfFriends
    store.clear
    rawFriends.size === numOfFriends
  }

}
