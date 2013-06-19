package com.keepit.common.social

import org.specs2.mutable._

import com.keepit.common.db.slick.Database
import com.keepit.common.net.FakeHttpClient
import com.keepit.inject._
import com.keepit.model.SocialUserInfo
import com.keepit.model.SocialUserInfoRepo
import com.keepit.model.User
import com.keepit.test._

import play.api.Play.current
import play.api.test.Helpers._
import securesocial.core._

class LinkedInSocialGraphTest extends Specification with DbRepos {

  private def urlIsConnections(url: String): Boolean = {
    url.startsWith("http://api.linkedin.com/v1/people/rFOBMp35vZ/connections?format=json") &&
      url.contains("oauth_consumer_key=ovlhms1y0fjr") &&
      url.contains("oauth_token=a27da99f-3e1f-4fb6-9261-944b3d1a8464")
  }

  private def urlIsProfile(url: String): Boolean = {
    url.startsWith("http://api.linkedin.com/v1/people/rFOBMp35vZ:(id,firstName,lastName,emailAddress,pictureUrl)?format=json")
  }

  "LinkedInSocialGraph" should {
    "fetch from linkedin" in {
      running(new EmptyApplication()) {
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
      "apiStandardProfileRequest": {
        "headers": {
          "_total": 1,
          "values": [
            {
              "name": "x-li-auth-token",
              "value": "name:A1Fh"
            }
          ]
        },
        "url": "http://api.linkedin.com/v1/people/PikD5Mqjb0"
      },
      "firstName": "Dan",
      "headline": "Founder & CEO at FortyTwo Inc",
      "id": "PikD5Mqjb0",
      "industry": "Internet",
      "lastName": "Blumenfeld",
      "location": {
        "country": {
          "code": "us"
        },
        "name": "San Francisco Bay Area"
      },
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_R7Vm6CddBow0oFjzvHRl6hfqcSOfECjzMDaA6heqHeo0v6ovBWoCe8pVJiYrd5pJVu4KdbnsYgzj",
      "siteStandardProfileRequest": {
        "url": "http://www.linkedin.com/profile/view?id=7564203&authType=name&authToken=A1Fh&trk=api*a287404*s294991*"
      }
    },
    {
      "apiStandardProfileRequest": {
        "headers": {
          "_total": 1,
          "values": [
            {
              "name": "x-li-auth-token",
              "value": "name:eiMi"
            }
          ]
        },
        "url": "http://api.linkedin.com/v1/people/fmTmo2dxFE"
      },
      "firstName": "Effi",
      "headline": "Product Manager at FortyTwo Inc.",
      "id": "fmTmo2dxFE",
      "industry": "Internet",
      "lastName": "Fuks Leichtag",
      "location": {
        "country": {
          "code": "us"
        },
        "name": "San Francisco Bay Area"
      },
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_IP83M2Gj1XuwK92_Wn_OMm5p15MdKvS_dtNYM7ka2iSJ7AsibvKDcfrhjeJ91tu7oKC0nH9cxOmI",
      "siteStandardProfileRequest": {
        "url": "http://www.linkedin.com/profile/view?id=13140558&authType=name&authToken=eiMi&trk=api*a287404*s294991*"
      }
    },
    {
      "apiStandardProfileRequest": {
        "headers": {
          "_total": 1,
          "values": [
            {
              "name": "x-li-auth-token",
              "value": "name:AIy3"
            }
          ]
        },
        "url": "http://api.linkedin.com/v1/people/-XI0AGcm7x"
      },
      "firstName": "Léo",
      "headline": "Software Engineer at FortyTwo Inc.",
      "id": "-XI0AGcm7x",
      "industry": "Information Technology and Services",
      "lastName": "Grimaldi",
      "location": {
        "country": {
          "code": "us"
        },
        "name": "San Francisco Bay Area"
      },
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_9G8uJ82Ox8f-craGZFl0Ji2PP_VjzvOGqQN1JiSauLaDoAdCsXK8B_JhgmsGnt0ac3Ct9k15U0Ix",
      "siteStandardProfileRequest": {
        "url": "http://www.linkedin.com/profile/view?id=17558679&authType=name&authToken=AIy3&trk=api*a287404*s294991*"
      }
    },
    {
      "apiStandardProfileRequest": {
        "headers": {
          "_total": 1,
          "values": [
            {
              "name": "x-li-auth-token",
              "value": "name:P08q"
            }
          ]
        },
        "url": "http://api.linkedin.com/v1/people/Aor3grqQ9s"
      },
      "firstName": "Jared",
      "headline": "Software Engineer at FortyTwo Inc.",
      "id": "Aor3grqQ9s",
      "industry": "Computer Software",
      "lastName": "Jacobs",
      "location": {
        "country": {
          "code": "us"
        },
        "name": "San Francisco Bay Area"
      },
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_JcF2qhBjP5D3t9arMz9PqCvhPi0hY9xrU91PqCvju6IrTteKvAchsG31gSx0jAYp4qL-RLbZxJnf",
      "siteStandardProfileRequest": {
        "url": "http://www.linkedin.com/profile/view?id=6214835&authType=name&authToken=P08q&trk=api*a287404*s294991*"
      }
    },
    {
      "apiStandardProfileRequest": {
        "headers": {
          "_total": 1,
          "values": [
            {
              "name": "x-li-auth-token",
              "value": "name:IsVM"
            }
          ]
        },
        "url": "http://api.linkedin.com/v1/people/AN0i5eHLth"
      },
      "firstName": "Yasuhiro",
      "headline": "Software Engineer at FortyTwo Inc.",
      "id": "AN0i5eHLth",
      "industry": "Internet",
      "lastName": "Matsuda",
      "location": {
        "country": {
          "code": "us"
        },
        "name": "San Francisco Bay Area"
      },
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_5R4EHmBnX7yBaa7QIyRvHDGE6uZBaSOQFM2zHDrnJWubAdd6djHQQSkZHG4eSE0Ek4VNFEa26T7w",
      "siteStandardProfileRequest": {
        "url": "http://www.linkedin.com/profile/view?id=8658284&authType=name&authToken=IsVM&trk=api*a287404*s294991*"
      }
    },
    {
      "apiStandardProfileRequest": {
        "headers": {
          "_total": 1,
          "values": [
            {
              "name": "x-li-auth-token",
              "value": "name:WpMt"
            }
          ]
        },
        "url": "http://api.linkedin.com/v1/people/7fohLXwKJU"
      },
      "firstName": "Yingjie",
      "headline": "Software Engineer at FortyTwo Inc",
      "id": "7fohLXwKJU",
      "industry": "Internet",
      "lastName": "Miao",
      "location": {
        "country": {
          "code": "us"
        },
        "name": "San Francisco Bay Area"
      },
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_H1YHLfkAkfab1fgNEASULuLyk2jelupNoBEBLSF8UEdZmHIqkzmXwDPmE3gB-wjZdry9oovfH8zk",
      "siteStandardProfileRequest": {
        "url": "http://www.linkedin.com/profile/view?id=70284958&authType=name&authToken=WpMt&trk=api*a287404*s294991*"
      }
    },
    {
      "apiStandardProfileRequest": {
        "headers": {
          "_total": 1,
          "values": [
            {
              "name": "x-li-auth-token",
              "value": "name:b0zF"
            }
          ]
        },
        "url": "http://api.linkedin.com/v1/people/xwzHI1G35O"
      },
      "firstName": "Ketan",
      "headline": "Intern at FortyTwo Inc.",
      "id": "xwzHI1G35O",
      "lastName": "Patel",
      "location": {
        "country": {
          "code": "us"
        },
        "name": "San Francisco Bay Area"
      },
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_HOKl9HzR0Cj53vR0EMPm9wvz0GmeiPE0oJka9wbXh3sZpcgxk48gZI1oAEaB8Nw1djt7J2v4WUit",
      "siteStandardProfileRequest": {
        "url": "http://www.linkedin.com/profile/view?id=171296965&authType=name&authToken=b0zF&trk=api*a287404*s294991*"
      }
    },
    {
      "apiStandardProfileRequest": {
        "headers": {
          "_total": 1,
          "values": [
            {
              "name": "x-li-auth-token",
              "value": "name:sP6d"
            }
          ]
        },
        "url": "http://api.linkedin.com/v1/people/94y_yO5fYU"
      },
      "firstName": "Eishay",
      "headline": "Founder/CTO at FortyTwo Inc.",
      "id": "94y_yO5fYU",
      "industry": "Computer Software",
      "lastName": "Smith",
      "location": {
        "country": {
          "code": "us"
        },
        "name": "San Francisco Bay Area"
      },
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_pl4fenTiB2Pj0CawOvZpez9GBu6Sx3OwYK2Kez61eW-g85dIKBHi6vtjMGQtp60bjtVAbtNe8-mP",
      "siteStandardProfileRequest": {
        "url": "http://www.linkedin.com/profile/view?id=7988163&authType=name&authToken=sP6d&trk=api*a287404*s294991*"
      }
    },
    {
      "apiStandardProfileRequest": {
        "headers": {
          "_total": 1,
          "values": [
            {
              "name": "x-li-auth-token",
              "value": "name:cp1l"
            }
          ]
        },
        "url": "http://api.linkedin.com/v1/people/kLoN_aF4zS"
      },
      "firstName": "Tamila",
      "headline": "Company Assistant at FortyTwo Inc.",
      "id": "kLoN_aF4zS",
      "industry": "Internet",
      "lastName": "Stavinsky",
      "location": {
        "country": {
          "code": "us"
        },
        "name": "San Francisco Bay Area"
      },
      "pictureUrl": "http://m3.licdn.com/mpr/mprx/0_lwfCFPmsNw5tbQjc1m2gFAfBNel-5LDc0fRjFrpWwa5GsTVBjS0mWKebs5AD6G2RKeDyHBaq8eq4",
      "siteStandardProfileRequest": {
        "url": "http://www.linkedin.com/profile/view?id=189410648&authType=name&authToken=cp1l&trk=api*a287404*s294991*"
      }
    },
    {
      "id": "private",
      "firstName": "private",
      "lastName": "private"
    }
  ]
}
"""
}
