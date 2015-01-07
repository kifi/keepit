package com.keepit.model

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class LibraryViewTest extends Specification {
  "BasicUserWithFriendStatus" should {
    "serialize to JSON" in {
      Json.toJson(BasicUserWithFriendStatus(
        externalId = ExternalId[User]("58328718-0222-47bf-9b12-d2d781cb8b0c"),
        firstName = "Chris",
        lastName = "Christie",
        pictureName = "1.jpg",
        username = Username("cc"),
        isFriend = Some(false),
        friendRequestSentAt = Some(new DateTime(23948192381L)),
        friendRequestReceivedAt = None)) ===
        Json.parse("""{
          "id": "58328718-0222-47bf-9b12-d2d781cb8b0c",
          "firstName": "Chris",
          "lastName": "Christie",
          "pictureName": "1.jpg",
          "username": "cc",
          "isFriend": false,
          "friendRequestSentAt": 23948192381
        }""")

      Json.toJson(BasicUserWithFriendStatus(
        externalId = ExternalId[User]("9813c3a2-f283-4056-ac9f-04d2e39d15a2"),
        firstName = "Janet",
        lastName = "Jackson",
        pictureName = "2.jpg",
        username = Username("jj"),
        isFriend = None,
        friendRequestSentAt = None,
        friendRequestReceivedAt = None)) ===
        Json.parse("""{
          "id": "9813c3a2-f283-4056-ac9f-04d2e39d15a2",
          "firstName": "Janet",
          "lastName": "Jackson",
          "pictureName": "2.jpg",
          "username": "jj"
        }""")
    }
  }
}
