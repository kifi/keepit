package com.keepit.common.social

import org.specs2.mutable._

import com.keepit.common.db.slick.Database
import com.keepit.common.net.FakeHttpClient
import com.keepit.inject._
import com.keepit.model.SocialUserInfo
import com.keepit.model.SocialUserInfoRepo
import com.keepit.model.User
import com.keepit.test._

import play.api.test.Helpers._
import securesocial.core._

class LinkedInSocialGraphTest extends Specification with ApplicationInjector with ShoeboxInjectionHelpers {

  private def urlIsConnections(url: String): Boolean = {
    url.startsWith("http://api.linkedin.com/v1/people/rFOBMp35vZ/connections:(id,firstName,lastName,pictureUrl,publicProfileUrl)?format=json") &&
      url.contains("oauth_consumer_key=ovlhms1y0fjr") &&
      url.contains("oauth_token=a27da99f-3e1f-4fb6-9261-944b3d1a8464")
  }

  private def urlIsProfile(url: String): Boolean = {
    url.startsWith("http://api.linkedin.com/v1/people/rFOBMp35vZ:(id,firstName,lastName,emailAddress,pictureUrl)?format=json")
  }

  "LinkedInSocialGraph" should {
    "fetch from linkedin" in {
      running(new DeprecatedEmptyApplication()) {
        val httpClient = new FakeHttpClient(Some({
          case url if urlIsConnections(url) => connectionsJson
          case url if urlIsProfile(url) => profileJson
        }))

        val oAuth1Info = OAuth1Info("a27da99f-3e1f-4fb6-9261-944b3d1a8464", "9e3a1cfe-36c4-406f-903a-13c04d41e42b")
        val socialUser = SocialUser(UserId("rFOBMp35vZ", "linkedin"), "Greg", "Methvin", "Greg Methvin",
          None, None, AuthenticationMethod.OAuth1, Some(oAuth1Info), None, None)

        val user = inject[Database].readWrite { implicit s =>
          userRepo.save(User(firstName = "Greg", lastName = "Methvin"))
        }
        val unsaved = SocialUserInfo(userId = user.id, fullName = "Greg Methvin", socialId = SocialId("rFOBMp35vZ"),
          networkType = SocialNetworks.LINKEDIN, credentials = Some(socialUser))
        val socialUserInfo = inject[Database].readWrite { implicit s =>
          inject[SocialUserInfoRepo].save(unsaved)
        }
        unsaved.userId === user.id
        socialUserInfo.userId === user.id
        socialUserInfo.fullName === "Greg Methvin"
        socialUserInfo.socialId.id === "rFOBMp35vZ"
        socialUserInfo.credentials.get === socialUser

        val graph = new LinkedInSocialGraph(httpClient)
        val rawInfo = graph.fetchSocialUserRawInfo(socialUserInfo).get
        rawInfo.fullName === "Greg Methvin"
        rawInfo.userId === socialUserInfo.userId
        rawInfo.socialId.id === "rFOBMp35vZ"

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
  "firstName": "Greg",
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
