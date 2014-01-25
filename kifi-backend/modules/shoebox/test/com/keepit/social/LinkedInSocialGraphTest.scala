package com.keepit.common.social

import org.specs2.mutable._

import com.keepit.common.db.slick.Database
import com.keepit.common.net.{FakeClientResponse, FakeHttpClientModule, HttpUri}
import com.keepit.model.SocialUserInfo
import com.keepit.model.SocialUserInfoRepo
import com.keepit.model.User
import com.keepit.test._

import play.api.test.Helpers._
import securesocial.core._
import com.keepit.social.{SocialNetworks, SocialId}

class LinkedInSocialGraphTest extends Specification with ShoeboxTestInjector {

  import LinkedInSocialGraph.ProfileFieldSelector

  private def urlIsConnections(url: String): Boolean = {
    url.startsWith(s"https://api.linkedin.com/v1/people/rFOBMp35vZ/connections:$ProfileFieldSelector?format=json") &&
      url.contains("oauth2_access_token=this_is_my_token")
  }

  private def urlIsProfile(url: String): Boolean = {
    url.startsWith(s"https://api.linkedin.com/v1/people/rFOBMp35vZ:$ProfileFieldSelector?format=json")
  }

  private val fakeLinkedInResponse: PartialFunction[HttpUri, FakeClientResponse] = {
    case url if urlIsConnections(url.url) => connectionsJson
    case url if urlIsProfile(url.url) => profileJson
  }

  "LinkedInSocialGraph" should {
    "fetch from linkedin" in {
      withDb(FakeHttpClientModule(fakeLinkedInResponse)) { implicit injector =>

        val oAuth2Info = OAuth2Info("this_is_my_token")
        val socialUser = SocialUser(IdentityId("rFOBMp35vZ", "linkedin"), "Greg", "Methvin", "Greg Methvin",
          None, None, AuthenticationMethod.OAuth2, None, Some(oAuth2Info), None)

        val user = inject[Database].readWrite { implicit s =>
          userRepo.save(User(firstName = "Greg", lastName = "Methvin"))
        }
        val unsaved = SocialUserInfo(userId = user.id, fullName = "Greg Methvin", socialId = SocialId("rFOBMp35vZ"),
          networkType = SocialNetworks.LINKEDIN, credentials = Some(socialUser))
        val socialUserInfo = inject[Database].readWrite { implicit s =>
          inject[SocialUserInfoRepo].save(unsaved)
        }
        println("SOCIALUSERINFO: " + socialUserInfo)
        unsaved.userId === user.id
        socialUserInfo.userId === user.id
        socialUserInfo.fullName === "Greg Methvin"
        socialUserInfo.socialId.id === "rFOBMp35vZ"
        socialUserInfo.credentials.get === socialUser

        val graph = inject[LinkedInSocialGraph]
        val rawInfo = graph.fetchSocialUserRawInfo(socialUserInfo).get
        rawInfo.fullName === "Greg Methvin"
        rawInfo.userId === socialUserInfo.userId
        rawInfo.socialId.id === "rFOBMp35vZ"

        val updated = rawInfo.jsons.foldLeft(socialUserInfo)(graph.updateSocialUserInfo)
        updated.fullName === "Gregory Methvin"
        updated.pictureUrl must beSome("http://m3.licdn.com/mpr/mprx/0_VqIaHTv7Z7Iiji26sv2AHik8NuM30Qp64z0lHiBYwoSAhGIQn-RGQ_iKsTJOyTjoRcdrFkWGuL1A")

        val connections = rawInfo.jsons flatMap graph.extractFriends
        connections.length === 9
        connections exists { conn =>
          conn.fullName == "Eishay Smith" &&
          conn.pictureUrl.get == "http://m3.licdn.com/mpr/mprx/0_pl4fenTiB2Pj0CawOvZpez9GBu6Sx3OwYK2Kez61eW-g85dIKBHi6vtjMGQtp60bjtVAbtNe8-mP"
        } must beTrue
      }
    }
  }

  private val profileJson =
"""
{
  "emailAddress": "greg.methvin@gmail.com",
  "firstName": "Gregory",
  "id": "rFOBMp35vZ",
  "lastName": "Methvin",
  "pictureUrls": {
    "_total": 1,
    "values": ["http://m3.licdn.com/mpr/mprx/0_VqIaHTv7Z7Iiji26sv2AHik8NuM30Qp64z0lHiBYwoSAhGIQn-RGQ_iKsTJOyTjoRcdrFkWGuL1A"]
  }
}
"""

  private val connectionsJson =
"""
{
  "_count": 10,
  "_start": 0,
  "_total": 11,
  "values": [
    {
      "firstName": "Dan",
      "id": "PikD5Mqjb0",
      "lastName": "Blumenfeld",
      "pictureUrls": {
        "_total": 1,
        "values": ["https://media.licdn.com/mpr/mprx/0_GZpK4xSqB5XZXGDhTsOYgYmnzczZL_Th84CjOYPVRGNRGC1mGegyyZnFrMC"]
      },
      "publicProfileUrl": "http://www.linkedin.com/in/danblumenfeld"
    },
    {
      "firstName": "Effi",
      "id": "fmTmo2dxFE",
      "lastName": "Fuks Leichtag",
      "pictureUrls": {
        "_total": 1,
        "values": ["https://media.licdn.com/mpr/mprx/0_GZpK4xSqB5XZXGDhTsOYgYmnzczZL_Th84CjOYPVRGNRGC1mGegyyZnFrMC"]
      },
      "publicProfileUrl": "http://www.linkedin.com/in/effifuks"
    },
    {
      "firstName": "Léo",
      "id": "-XI0AGcm7x",
      "lastName": "Grimaldi",
      "pictureUrls": {
        "_total": 1,
        "values": ["https://media.licdn.com/mpr/mprx/0_GZpK4xSqB5XZXGDhTsOYgYmnzczZL_Th84CjOYPVRGNRGC1mGegyyZnFrMC"]
      },
      "publicProfileUrl": "http://www.linkedin.com/in/leogrimaldi"
    },
    {
      "firstName": "Jared",
      "id": "Aor3grqQ9s",
      "lastName": "Jacobs",
      "pictureUrls": {
        "_total": 1,
        "values": ["https://media.licdn.com/mpr/mprx/0_GZpK4xSqB5XZXGDhTsOYgYmnzczZL_Th84CjOYPVRGNRGC1mGegyyZnFrMC"]
      },
      "publicProfileUrl": "http://www.linkedin.com/in/jaredjacobs"
    },
    {
      "firstName": "Yasuhiro",
      "id": "AN0i5eHLth",
      "lastName": "Matsuda",
      "pictureUrls": {
        "_total": 1,
        "values": ["https://media.licdn.com/mpr/mprx/0_GZpK4xSqB5XZXGDhTsOYgYmnzczZL_Th84CjOYPVRGNRGC1mGegyyZnFrMC"]
      },
      "publicProfileUrl": "http://www.linkedin.com/in/ymatsuda"
    },
    {
      "firstName": "Yingjie",
      "id": "7fohLXwKJU",
      "lastName": "Miao",
      "pictureUrls": {
        "_total": 1,
        "values": ["https://media.licdn.com/mpr/mprx/0_GZpK4xSqB5XZXGDhTsOYgYmnzczZL_Th84CjOYPVRGNRGC1mGegyyZnFrMC"]
      },
      "publicProfileUrl": "http://www.linkedin.com/pub/yingjie-miao/1b/656/1ba"
    },
    {
      "firstName": "Ketan",
      "id": "xwzHI1G35O",
      "lastName": "Patel",
      "pictureUrls": {
        "_total": 1,
        "values": ["https://media.licdn.com/mpr/mprx/0_GZpK4xSqB5XZXGDhTsOYgYmnzczZL_Th84CjOYPVRGNRGC1mGegyyZnFrMC"]
      },
      "publicProfileUrl": "http://www.linkedin.com/in/ketanpatel3"
    },
    {
      "firstName": "Eishay",
      "id": "94y_yO5fYU",
      "lastName": "Smith",
      "pictureUrls": {
        "_total": 1,
        "values": ["http://m3.licdn.com/mpr/mprx/0_pl4fenTiB2Pj0CawOvZpez9GBu6Sx3OwYK2Kez61eW-g85dIKBHi6vtjMGQtp60bjtVAbtNe8-mP"]
      },
      "publicProfileUrl": "http://www.linkedin.com/in/eishay"
    },
    {
      "firstName": "Tamila",
      "id": "kLoN_aF4zS",
      "industry": "Internet",
      "lastName": "Stavinsky",
      "pictureUrls": {
        "_total": 1,
        "values": ["https://media.licdn.com/mpr/mprx/0_GZpK4xSqB5XZXGDhTsOYgYmnzczZL_Th84CjOYPVRGNRGC1mGegyyZnFrMC"]
      },
      "publicProfileUrl": "http://www.linkedin.com/pub/tamila-stavinsky/53/524/788"
    },
    {
      "firstName": "private",
      "id": "private",
      "lastName": "private"
    }
  ]
}
"""
}
