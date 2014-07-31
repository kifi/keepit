package com.keepit.common.social

import scala.collection.mutable.Map

import java.io.File

import org.specs2.mutable._

import com.keepit.common.db.Id
import com.keepit.inject._
import com.keepit.model.{ SocialUserInfoRepo, User, UserRepo, SocialUserInfo }
import com.keepit.test.{ ShoeboxTestInjector, CommonTestInjector, DeprecatedEmptyApplication }

import play.api.libs.json.Json
import play.api.test.Helpers._
import com.google.inject.Injector
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.social.{ SocialNetworks, SocialId, SocialUserRawInfo, SocialUserRawInfoStore }
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.FakeMailModule

class SocialUserImportFriendsTest extends Specification with ShoeboxTestInjector {

  "SocialUserImportFriends" should {
    "import friends" in {
      withDb(FakeHttpClientModule(), ShoeboxFakeStoreModule(), FakeMailModule()) { implicit injector =>
        val graphs = List(
          ("facebook_graph_andrew_min.json", 7),
          ("facebook_graph_eishay_super_min.json", 5),
          ("facebook_graph_eishay_no_friends.json", 0),
          ("facebook_graph_shawn.json", 82)
        )
        val socialUser = inject[Database].readWrite { implicit s =>
          val u = inject[UserRepo].save(User(firstName = "Andrew", lastName = "Conner"))
          val su = inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Andrew Conner",
            socialId = SocialId("7110335121"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(u))
          su
        }
        graphs map { case (filename, numOfFriends) => testFacebookGraph(socialUser, filename, numOfFriends) }

      }
    }
  }

  def testFacebookGraph(socialUserInfo: SocialUserInfo, jsonFilename: String, numOfFriends: Int)(implicit injector: Injector) = {
    val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/%s".format(jsonFilename))).mkString)
    val extractedFriends = inject[FacebookSocialGraph].extractFriends(json)
    val socialUsers = inject[SocialUserImportFriends].importFriends(socialUserInfo, extractedFriends)
    socialUsers.size === numOfFriends
  }

}
