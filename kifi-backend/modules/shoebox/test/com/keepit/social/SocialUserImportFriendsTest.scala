package com.keepit.common.social

import scala.collection.mutable.Map

import java.io.File

import org.specs2.mutable._

import com.keepit.common.db.Id
import com.keepit.inject._
import com.keepit.model.SocialUserInfo
import com.keepit.test.{ShoeboxTestInjector, TestInjector, DeprecatedEmptyApplication}

import play.api.libs.json.Json
import play.api.test.Helpers._
import com.google.inject.Injector
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.ShoeboxFakeStoreModule

class SocialUserImportFriendsTest extends Specification with ShoeboxTestInjector {

  "SocialUserImportFriends" should {
    "import friends" in {
      withDb(FakeHttpClientModule(), ShoeboxFakeStoreModule()) { implicit injector =>
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

  def testFacebookGraph(jsonFilename: String, numOfFriends: Int)(implicit injector: Injector) = {
    val json = Json.parse(io.Source.fromFile(new File("modules/shoebox/test/com/keepit/common/social/data/%s".format(jsonFilename))).mkString)
    val extractedFriends = inject[FacebookSocialGraph].extractFriends(json)
    val rawFriends = inject[SocialUserImportFriends].importFriends(extractedFriends)
    val store = inject[SocialUserRawInfoStore].asInstanceOf[Map[Id[SocialUserInfo], SocialUserRawInfo]]
    store.size === numOfFriends
    store.clear()
    rawFriends.size === numOfFriends
  }

}
