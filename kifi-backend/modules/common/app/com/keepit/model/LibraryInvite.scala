package com.keepit.model

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.crypto.{ CryptoSupport, ModelWithPublicIdCompanion, ModelWithPublicId }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.shoebox.Words
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.QueryStringBindable
import scala.concurrent.duration.Duration
import scala.util.Random

case class LibraryInvite(
    id: Option[Id[LibraryInvite]] = None,
    libraryId: Id[Library],
    inviterId: Id[User],
    userId: Option[Id[User]] = None,
    emailAddress: Option[EmailAddress] = None,
    access: LibraryAccess,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryInvite] = LibraryInviteStates.ACTIVE,
    authToken: String = RandomStringUtils.randomAlphanumeric(32),
    passPhrase: String = LibraryInvite.generatePassPhrase(),
    message: Option[String] = None) extends ModelWithPublicId[LibraryInvite] with ModelWithState[LibraryInvite] {

  def withId(id: Id[LibraryInvite]): LibraryInvite = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): LibraryInvite = this.copy(updatedAt = now)
  def withState(newState: State[LibraryInvite]): LibraryInvite = this.copy(state = newState)

  def isCollaborator = (access == LibraryAccess.READ_WRITE) || (access == LibraryAccess.READ_INSERT)
  def isFollower = (access == LibraryAccess.READ_ONLY)

  override def toString: String = s"LibraryInvite[id=$id,libraryId=$libraryId,ownerId=$inviterId,userId=$userId,email=$emailAddress,access=$access,state=$state]"
}

object LibraryInvite extends ModelWithPublicIdCompanion[LibraryInvite] {

  protected[this] val publicIdPrefix = "l"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-20, -76, -59, 85, 85, -2, 72, 61, 58, 38, 60, -2, -128, 79, 9, -87))

  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[LibraryInvite]) and
    (__ \ 'libraryId).format[Id[Library]] and
    (__ \ 'inviterId).format[Id[User]] and
    (__ \ 'userId).format[Option[Id[User]]] and
    (__ \ 'emailAddress).format[Option[EmailAddress]] and
    (__ \ 'access).format[LibraryAccess] and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[LibraryInvite]) and
    (__ \ 'authToken).format[String] and
    (__ \ 'passPhrase).format[String] and
    (__ \ 'message).format[Option[String]]
  )(LibraryInvite.apply, unlift(LibraryInvite.unapply))

  implicit def ord: Ordering[LibraryInvite] = new Ordering[LibraryInvite] {
    def compare(x: LibraryInvite, y: LibraryInvite): Int = x.access.priority compare y.access.priority
  }

  def generatePassPhrase(): String = {
    // each word has length 4-8
    val randomNoun = Words.nouns(Random.nextInt(Words.nouns.length))
    val randomAdverb = Words.adverbs(Random.nextInt(Words.adverbs.length))
    val randomAdjective = Words.adjectives(Random.nextInt(Words.adjectives.length))
    randomAdverb + " " + randomAdjective + " " + randomNoun
  }
}

case class HashedPassPhrase(value: String)
object HashedPassPhrase {
  implicit def queryStringBinder(implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[HashedPassPhrase] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, HashedPassPhrase]] = {
      stringBinder.bind(key, params) map {
        case Right(phrase) => Right(HashedPassPhrase(phrase))
        case _ => Left("Not a valid pass phrase")
      }
    }
    override def unbind(key: String, hash: HashedPassPhrase): String = {
      stringBinder.unbind(key, hash.value)
    }
  }

  implicit def format: Format[HashedPassPhrase] =
    Format(__.read[String].map(HashedPassPhrase(_)),
      new Writes[HashedPassPhrase] { def writes(o: HashedPassPhrase) = JsString(o.value) })

  def generateHashedPhrase(value: String): HashedPassPhrase = {
    val cleaned = value.toLowerCase.replaceAll("[^a-z]", "")
    HashedPassPhrase(CryptoSupport.generateHexSha256(cleaned))
  }
}

// Not sure we need this cache?
case class LibraryInviteIdKey(id: Id[LibraryInvite]) extends Key[LibraryInvite] {
  val namespace = "library_invite_by_id"
  override val version: Int = 2
  def toKey(): String = id.id.toString
}
class LibraryInviteIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryInviteIdKey, LibraryInvite](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object LibraryInviteStates extends States[LibraryInvite] {
  val ACCEPTED = State[LibraryInvite]("accepted")
  val DECLINED = State[LibraryInvite]("declined")
}
