package com.keepit.model

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.social.BasicUser
import com.keepit.social.BasicUser.{ mapUserIdToUserIdSet, mapUserIdToBasicUser, mapUserIdToInt }
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class BasicUserTest extends Specification {

  "BasicUser formats" should {
    val basicUser1 = BasicUser(externalId = ExternalId[User]("58328718-0222-47bf-9b12-d2d781cb8b0c"), firstName = "Chris", lastName = "Christie", pictureName = "1.jpg", username = Username("cc"), active = true)
    val basicUser2 = BasicUser(externalId = ExternalId[User]("9813c3a2-f283-4056-ac9f-04d2e39d15a2"), firstName = "Janet", lastName = "Jackson", pictureName = "2.jpg", username = Username("jj"), active = true)
    val userId1 = Id[User](1)
    val userId2 = Id[User](2)
    val userId3 = Id[User](3)
    val userId4 = Id[User](4)

    "format Map of Id[User] -> BasicUser" in {
      val in = Map(userId1 -> basicUser1, userId2 -> basicUser2)
      Json.fromJson[Map[Id[User], BasicUser]](Json.toJson(in)).get === in
    }

    "format Map of Id[User] to Set[Id[User]" in {
      val in = Map(
        userId1 -> Set[Id[User]](userId2, userId3),
        userId2 -> Set[Id[User]](userId1, userId4),
        userId3 -> Set[Id[User]]()
      )
      Json.fromJson[Map[Id[User], Set[Id[User]]]](Json.toJson(in)).get === in
    }

    "format Map of Id[User] to Int" in {
      val in = Map(userId1 -> 5, userId2 -> 6, userId3 -> 7, userId4 -> 0)
      Json.fromJson[Map[Id[User], Int]](Json.toJson(in)).get === in
    }

  }

}
