package com.keepit.social

import com.keepit.common.cache._
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.Id.mapOfIdToObjectFormat
import com.keepit.common.logging.AccessLog
import com.keepit.common.path.Path
import com.keepit.common.store.{ ImagePath, S3ImageConfig, S3UserPictureConfig }
import com.keepit.model._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scala.annotation.switch
import scala.concurrent.duration.Duration
import play.api.Play

import scala.util.hashing.MurmurHash3

import scala.util.Random

trait BasicUserFields {
  def externalId: ExternalId[User]
  def firstName: String
  def lastName: String
  def pictureName: String
  def username: Username
  def fullName: String = s"$firstName $lastName"
}

object BasicUserFields {
  val format =
    (__ \ 'id).format[ExternalId[User]] and
      (__ \ 'firstName).format[String] and
      (__ \ 'lastName).format[String] and
      (__ \ 'pictureName).format[String] and
      (__ \ 'username).format[Username]
}

case class BasicUser(
    externalId: ExternalId[User],
    firstName: String,
    lastName: String,
    pictureName: String,
    username: Username) extends BasicUserFields {
  def path: Path = Path(username.value)
  def picturePath: ImagePath = UserPicture.toImagePath(Some(200), externalId, pictureName)
}

object BasicUser {
  private implicit val userExternalIdFormat = ExternalId.format[User]
  private implicit val usernameFormat = Username.jsonAnnotationFormat

  // Be aware that BasicUserLikeEntity uses the `kind` field to detect if its a BasicUser or BasicNonUser
  implicit val format = (BasicUserFields.format)(BasicUser.apply, unlift(BasicUser.unapply))

  implicit val mapUserIdToInt = mapOfIdToObjectFormat[User, Int]
  implicit val mapUserIdToBasicUser = mapOfIdToObjectFormat[User, BasicUser]
  implicit val mapUserIdToUserIdSet = mapOfIdToObjectFormat[User, Set[Id[User]]]

  // Public so I can avoid putting it in S3ImageStore, which will die.
  // Please don't use these a ton:

  def useDefaultImageForUser(user: User): Boolean = {
    import play.api.Play.current

    val useDefaultPics = Play.maybeApplication.exists(_ => !Play.isTest) // This is probably a bad thing to do in general
    if (useDefaultPics) {
      user.pictureName match {
        case Some(picName) if picName != "0" => false
        case _ =>
          true
      }
    } else {
      false
    }
  }

  def defaultImageForUserId(userId: Id[User]): String = {
    // Keep this stable! There are 42 images. If this is changed, treat old user ids differently.
    // The CDN will cache for 6 hours, so don't expect immediate updates. If you need changes, you can
    // modify this to be a function from id -> whatever file name you actually need
    chooseImage(userId.id)
  }

  def defaultImageForEmail(email: String): String = chooseImage(MurmurHash3.stringHash(email))

  private def chooseImage(randomLong: Long): String = {
    val variant = (randomLong % 42).toInt
    val choice = (variant: @switch) match {
      case 0 => "bat"
      case 1 => "bear"
      case 2 => "bird"
      case 3 => "bug"
      case 4 => "butterfly"
      case 5 => "camel"
      case 6 => "cat"
      case 7 => "cheetah"
      case 8 => "chicken"
      case 9 => "koala"
      case 10 => "cow"
      case 11 => "crocodile"
      case 12 => "dinosaur"
      case 13 => "dog"
      case 14 => "dove"
      case 15 => "duck"
      case 16 => "eagle"
      case 17 => "elephant"
      case 18 => "fish"
      case 19 => "flamingo"
      case 20 => "fly"
      case 21 => "fox"
      case 22 => "frog"
      case 23 => "giraffe"
      case 24 => "gorilla"
      case 25 => "horse"
      case 26 => "kangaroo"
      case 27 => "leopard"
      case 28 => "lion"
      case 29 => "monkey"
      case 30 => "mouse"
      case 31 => "panda"
      case 32 => "parrot"
      case 33 => "penguin"
      case 34 => "sheep"
      case 35 => "spider"
      case 36 => "squirrel"
      case 37 => "starfish"
      case 38 => "tiger"
      case 39 => "turtle"
      case 40 => "wolf"
      case 41 => "zebra"
      case _ => "dog" // the cutest
    }
    s"/default-pic/${choice}_200.png"
  }

  private def userToPictureName(user: User): String = {
    if (useDefaultImageForUser(user)) {
      val path = defaultImageForUserId(user.id.get)
      s"../../../..$path"
    } else {
      user.pictureName.getOrElse(S3UserPictureConfig.defaultName) + ".jpg"
    }
  }

  def fromUser(user: User): BasicUser = {
    BasicUser(
      externalId = user.externalId,
      firstName = user.firstName,
      lastName = user.lastName,
      pictureName = userToPictureName(user),
      username = user.username
    )
  }
}

case class BasicUserWithUrlIntersection(user: BasicUser, uriId: PublicId[NormalizedURI])
object BasicUserWithUrlIntersection {
  implicit val writes: Writes[BasicUserWithUrlIntersection] = Writes {
    case BasicUserWithUrlIntersection(user, uriId) => Json.toJson(user).as[JsObject] ++ Json.obj("intersection" -> s"/int?uri=${uriId.id}&user=${user.externalId.id}")
  }
}

case class BasicUserUserIdKey(userId: Id[User]) extends Key[BasicUser] {
  override val version = 13
  val namespace = "basic_user_userid"
  def toKey(): String = userId.id.toString
}

class BasicUserUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[BasicUserUserIdKey, BasicUser](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

// todo: Move to shared project between shoebox and search. This is a very specialized class that doesn't need to be in common
case class TypeaheadUserHit(
  userId: Id[User],
  externalId: ExternalId[User],
  firstName: String,
  lastName: String,
  pictureName: String)

object TypeaheadUserHit {
  private implicit val userIdFormat = Id.format[User]
  private implicit val userExternalIdFormat = ExternalId.format[User]

  implicit val basicUserWithUserIdFormat = (
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'externalId).format[ExternalId[User]] and
    (__ \ 'firstName).format[String] and
    (__ \ 'lastName).format[String] and
    (__ \ 'pictureName).format[String]
  )(TypeaheadUserHit.apply, unlift(TypeaheadUserHit.unapply))

  def fromBasicUserAndId(user: BasicUser, id: Id[User]): TypeaheadUserHit = {
    TypeaheadUserHit(id, user.externalId, user.firstName, user.lastName, user.pictureName)
  }
}
