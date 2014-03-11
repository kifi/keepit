package com.keepit.controllers.ext

import com.keepit.common.db.Id
import com.keepit.social.SocialId
import com.keepit.social.SocialNetworks.{FACEBOOK, LINKEDIN}
import com.keepit.model.{EContact, SocialUserBasicInfo, SocialUserInfo, User}

import org.joda.time.DateTime
import org.specs2.mutable.Specification

import play.api.libs.json.Json

class ExtNonUserSearchControllerTest extends Specification {

  "ExtNonUserSearchController" should {
    "serialize no results" in {
      new ExtNonUserSearchController(null, null, null, null, null)
      .serializeResults(Seq.empty, Seq.empty, Map.empty, Map.empty) === Json.arr()
    }

    "serialize some results" in {
      val infos = Seq(
        SocialUserBasicInfo(
          id = Id[SocialUserInfo](3),
          userId = None,
          fullName = "Joe Bob",
          pictureUrl = Some("http://fbcdn.net/p100x100/134_a.jpg"),
          socialId = SocialId("134"),
          networkType = FACEBOOK),
        SocialUserBasicInfo(
          id = Id[SocialUserInfo](7),
          userId = None,
          fullName = "Mae Rae",
          pictureUrl = None,
          socialId = SocialId("256"),
          networkType = LINKEDIN))

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
          name = None,
          firstName = Some("Bill"),
          lastName = Some("Watterson"),
          contactUserId = None))

      val infoInviteDates = Map(Id[SocialUserInfo](7) -> new DateTime(88888888888L))
      val contactInviteDates = Map(Id[EContact](8) -> new DateTime(99999999999L))

      new ExtNonUserSearchController(null, null, null, null, null)
      .serializeResults(infos, contacts, infoInviteDates, contactInviteDates) === Json.arr(
        Json.obj("name" -> "Joe Bob", "id" -> "facebook/134", "pic" -> "http://fbcdn.net/p100x100/134_a.jpg"),
        Json.obj("name" -> "Mae Rae", "id" -> "linkedin/256", "invited" -> 88888888888L),
        Json.obj("email" -> "jim@davis.name", "name" -> "James R. Davis", "invited" -> 99999999999L),
        Json.obj("email" -> "bill@wattersons.org"))
    }
  }

}
