package com.keepit.controllers.ext

import com.keepit.common.db.Id
import com.keepit.social.SocialId
import com.keepit.social.SocialNetworks.FACEBOOK
import com.keepit.model.{EContact, SocialUserBasicInfo, SocialUserInfo, User}

import org.specs2.mutable.Specification

import play.api.libs.json.Json

class ExtNonUserSearchControllerTest extends Specification {

  "ExtNonUserSearchController" should {
    "serialize no results" in {
      val controller = new ExtNonUserSearchController(null, null, null)
      controller.serializeResults(Seq.empty, Seq.empty) === Json.arr()
    }

    "serialize some results" in {
      val infos = Seq(
        SocialUserBasicInfo(
          id = Id[SocialUserInfo](3),
          userId = None,
          fullName = "Joe Bob",
          pictureUrl = Some("http://fbcdn.net/p100x100/134_a.jpg"),
          socialId = SocialId("facebook/134"),
          networkType = FACEBOOK),
        SocialUserBasicInfo(
          id = Id[SocialUserInfo](7),
          userId = None,
          fullName = "Mae Rae",
          pictureUrl = None,
          socialId = SocialId("facebook/256"),
          networkType = FACEBOOK))
      val contacts = Seq(
        EContact(
          id = Some(Id[EContact](8)),
          userId = Id[User](1),
          email = "jim@davis.name",
          name = Some("James R. Davis"),
          firstName = Some("Jim"),
          lastName = Some("Davis"),
          contactUserId = None),
        EContact(
          id = Some(Id[EContact](9)),
          userId = Id[User](1),
          email = "bill@wattersons.org",
          name = Some("William B. Watterson"),
          firstName = Some("Bill"),
          lastName = Some("Watterson"),
          contactUserId = None))
      val controller = new ExtNonUserSearchController(null, null, null)
      controller.serializeResults(infos, contacts) === Json.arr(
        Json.obj("name" -> "Joe Bob", "id" -> "facebook/134", "pic" -> "http://fbcdn.net/p100x100/134_a.jpg"),
        Json.obj("name" -> "Mae Rae", "id" -> "facebook/256"),
        Json.obj("name" -> "James R. Davis", "email" -> "jim@davis.name"),
        Json.obj("name" -> "William B. Watterson", "email" -> "bill@wattersons.org"))
    }
  }

}
