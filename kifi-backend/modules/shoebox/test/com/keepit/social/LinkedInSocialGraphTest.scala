package com.keepit.common.social

import org.specs2.mutable._

import com.keepit.common.db.slick.Database
import com.keepit.common.net.{FakeClientResponse, FakeHttpClientModule}
import com.keepit.model.SocialUserInfo
import com.keepit.model.SocialUserInfoRepo
import com.keepit.model.User
import com.keepit.test._

import play.api.test.Helpers._
import securesocial.core._
import com.keepit.social.{SocialNetworks, SocialId}

class LinkedInSocialGraphTest extends Specification with ShoeboxApplicationInjector {

  import LinkedInSocialGraph.ProfileFieldSelector

  private def urlIsConnections(url: String): Boolean = {
    url.startsWith(s"https://api.linkedin.com/v1/people/rFOBMp35vZ/connections:$ProfileFieldSelector?format=json") &&
      url.contains("oauth2_access_token=this_is_my_token")
  }

  private def urlIsProfile(url: String): Boolean = {
    url.startsWith(s"https://api.linkedin.com/v1/people/rFOBMp35vZ:$ProfileFieldSelector?format=json")
  }

  private val fakeLinkedInResponse: PartialFunction[String, FakeClientResponse] = {
    case url if urlIsConnections(url) => connectionsJson
    case url if urlIsProfile(url) => profileJson
  }

  "LinkedInSocialGraph" should {
    "fetch from linkedin" in {
      running(new ShoeboxApplication(FakeHttpClientModule(fakeLinkedInResponse))) {

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
          conn._1.fullName == "Eishay Smith" &&
          conn._1.pictureUrl.get == "http://m3.licdn.com/mpr/mprx/0_pl4fenTiB2Pj0CawOvZpez9GBu6Sx3OwYK2Kez61eW-g85dIKBHi6vtjMGQtp60bjtVAbtNe8-mP"
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
  "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_VqIaHTv7Z7Iiji26sv2AHik8NuM30Qp64z0lHiBYwoSAhGIQn-RGQ_iKsTJOyTjoRcdrFkWGuL1A"
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
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_R7Vm6CddBow0oFjzvHRl6hfqcSOfECjzMDaA6heqHeo0v6ovBWoCe8pVJiYrd5pJVu4KdbnsYgzj",
      "publicProfileUrl": "http://www.linkedin.com/in/danblumenfeld"
    },
    {
      "firstName": "Effi",
      "id": "fmTmo2dxFE",
      "lastName": "Fuks Leichtag",
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_IP83M2Gj1XuwK92_Wn_OMm5p15MdKvS_dtNYM7ka2iSJ7AsibvKDcfrhjeJ91tu7oKC0nH9cxOmI",
      "publicProfileUrl": "http://www.linkedin.com/in/effifuks"
    },
    {
      "firstName": "Léo",
      "id": "-XI0AGcm7x",
      "lastName": "Grimaldi",
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_9G8uJ82Ox8f-craGZFl0Ji2PP_VjzvOGqQN1JiSauLaDoAdCsXK8B_JhgmsGnt0ac3Ct9k15U0Ix",
      "publicProfileUrl": "http://www.linkedin.com/in/leogrimaldi"
    },
    {
      "firstName": "Jared",
      "id": "Aor3grqQ9s",
      "lastName": "Jacobs",
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_JcF2qhBjP5D3t9arMz9PqCvhPi0hY9xrU91PqCvju6IrTteKvAchsG31gSx0jAYp4qL-RLbZxJnf",
      "publicProfileUrl": "http://www.linkedin.com/in/jaredjacobs"
    },
    {
      "firstName": "Yasuhiro",
      "id": "AN0i5eHLth",
      "lastName": "Matsuda",
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_5R4EHmBnX7yBaa7QIyRvHDGE6uZBaSOQFM2zHDrnJWubAdd6djHQQSkZHG4eSE0Ek4VNFEa26T7w",
      "publicProfileUrl": "http://www.linkedin.com/in/ymatsuda"
    },
    {
      "firstName": "Yingjie",
      "id": "7fohLXwKJU",
      "lastName": "Miao",
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_H1YHLfkAkfab1fgNEASULuLyk2jelupNoBEBLSF8UEdZmHIqkzmXwDPmE3gB-wjZdry9oovfH8zk",
      "publicProfileUrl": "http://www.linkedin.com/pub/yingjie-miao/1b/656/1ba"
    },
    {
      "firstName": "Ketan",
      "id": "xwzHI1G35O",
      "lastName": "Patel",
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_HOKl9HzR0Cj53vR0EMPm9wvz0GmeiPE0oJka9wbXh3sZpcgxk48gZI1oAEaB8Nw1djt7J2v4WUit",
      "publicProfileUrl": "http://www.linkedin.com/in/ketanpatel3"
    },
    {
      "firstName": "Eishay",
      "id": "94y_yO5fYU",
      "lastName": "Smith",
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_pl4fenTiB2Pj0CawOvZpez9GBu6Sx3OwYK2Kez61eW-g85dIKBHi6vtjMGQtp60bjtVAbtNe8-mP",
      "publicProfileUrl": "http://www.linkedin.com/in/eishay"
    },
    {
      "firstName": "Tamila",
      "id": "kLoN_aF4zS",
      "industry": "Internet",
      "lastName": "Stavinsky",
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_lwfCFPmsNw5tbQjc1m2gFAfBNel-5LDc0fRjFrpWwa5GsTVBjS0mWKebs5AD6G2RKeDyHBaq8eq4",
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
