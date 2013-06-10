package com.keepit.common.social

import scala.collection.mutable.Map

import java.io.File

import org.specs2.mutable._

import com.keepit.common.db.Id
import com.keepit.inject._
import com.keepit.model.SocialUserInfo
import com.keepit.test.EmptyApplication

import play.api.Play.current
import play.api.libs.json.Json
import play.api.test.Helpers._

class SocialUserImportFriendsTest extends Specification {

  "SocialUserImportFriends" should {
    "import friends" in {
      running(new EmptyApplication().withFakeHttpClient()) {
        val graphs = List(
            ("facebook_graph_andrew_min.json", 7),
            ("facebook_graph_eishay_super_min.json", 5),
            ("facebook_graph_eishay_no_friends.json", 0),
            ("facebook_graph_shawn.json", 82)
        )
        graphs map { case (filename, numOfFriends) => testFacebookGraph(filename, numOfFriends) }

      }
    }
  }

  def testFacebookGraph(jsonFilename: String, numOfFriends: Int) = {
    val json = Json.parse(io.Source.fromFile(new File("modules/common/test/com/keepit/common/social/%s".format(jsonFilename))).mkString)
    val extractedFriends = inject[FacebookSocialGraph].extractFriends(json)
    val rawFriends = inject[SocialUserImportFriends].importFriends(extractedFriends, SocialNetworks.FACEBOOK)
    val store = inject[SocialUserRawInfoStore].asInstanceOf[Map[Id[SocialUserInfo], SocialUserRawInfo]]
    store.size === numOfFriends
    store.clear
    rawFriends.size === numOfFriends
  }

}
