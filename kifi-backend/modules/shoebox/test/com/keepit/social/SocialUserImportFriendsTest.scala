package com.keepit.common.social

import scala.collection.mutable.Map

import java.io.File

import org.specs2.mutable._

import com.keepit.model.{ Username, SocialUserInfoRepo, User, UserRepo, SocialUserInfo }
import com.keepit.test.{ ShoeboxTestInjector }

import play.api.libs.json.Json
import com.google.inject.Injector
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.social.{ SocialNetworks, SocialId }
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.FakeMailModule

class SocialUserImportFriendsTest extends Specification with ShoeboxTestInjector {

  "SocialUserImportFriends" should {
    "import friends" in {
      withDb(FakeHttpClientModule(), FakeShoeboxStoreModule(), FakeMailModule()) { implicit injector =>
        val graphs = List(
          ("facebook_graph_andrew_min.json", 7),
          ("facebook_graph_eishay_super_min.json", 5),
          ("facebook_graph_eishay_no_friends.json", 0),
          ("facebook_graph_shawn.json", 82)
        )
        val socialUser = inject[Database].readWrite { implicit s =>
          val u = inject[UserRepo].save(User(firstName = "Andrew", lastName = "Conner", username = Username("test"), normalizedUsername = "test", pictureName = Some("0")))
          val su = inject[SocialUserInfoRepo].save(SocialUserInfo(
            fullName = "Andrew Conner",
            socialId = SocialId("7110335121"),
            networkType = SocialNetworks.FACEBOOK
          ).withUser(u))
          su
        }
        graphs forall { case (filename, numOfFriends) => testFacebookGraph(socialUser, filename, numOfFriends) }
      }
    }
  }

  def testFacebookGraph(socialUserInfo: SocialUserInfo, jsonFilename: String, numOfFriends: Int)(implicit injector: Injector) = {
    val json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/%s".format(jsonFilename))).mkString)
    val extractedFriends = inject[FacebookSocialGraph].extractFriends(json)
    val socialUsers = inject[SocialUserImportFriends].importFriends(socialUserInfo, extractedFriends)
    socialUsers.size == numOfFriends
  }

}
