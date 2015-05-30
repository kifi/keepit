package com.keepit.model

import java.net.URLEncoder
import java.util.regex.Pattern

import com.keepit.common.crypto.ModelWithPublicId
import com.keepit.common.db._
import com.keepit.common.strings._
import com.keepit.common.time._
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.util.Random

case class Organization(
    id: Option[Id[Organization]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[Organization] = OrganizationStates.ACTIVE,
    seq: SequenceNumber[Organization] = SequenceNumber.ZERO,
    name: String,
    description: Option[String] = None,
    ownerId: Id[User],
    slug: OrganizationSlug) extends ModelWithPublicId[Organization] with ModelWithState[Organization] with ModelWithSeqNumber[Organization] {

  override def withId(id: Id[Organization]): Organization = this.copy(id = Some(id))

  override def withUpdateTime(now: DateTime): Organization = this.copy(updatedAt = now)
}

object Organization {
  implicit val format: Format[Organization] = (
    (__ \ 'id).formatNullable[Id[Organization]] and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format(State.format[Organization]) and
    (__ \ 'seq).format(SequenceNumber.format[Organization]) and
    (__ \ 'name).format[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'ownerId).format(Id.format[User]) and
    (__ \ 'slug).format[OrganizationSlug]
  )(Organization.apply, unlift(Organization.unapply))

  def applyFromDbRow(
    id: Option[Id[Organization]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[Organization],
    seq: SequenceNumber[Organization],
    name: String,
    description: Option[String],
    ownerId: Id[User],
    slug: OrganizationSlug) = {
    Organization(id, createdAt, updatedAt, state, seq, name, description, ownerId, slug)
  }

  def unapplyToDbRow(org: Organization) = {
    Some(
      org.id,
      org.createdAt,
      org.updatedAt,
      org.state,
      org.seq,
      org.name,
      org.description,
      org.ownerId,
      org.slug)
  }
}

case class OrganizationSlug(value: String) {
  def urlEncoded: String = URLEncoder.encode(value, UTF8)
}

object OrganizationSlug {
  implicit def format: Format[OrganizationSlug] =
    Format(__.read[String].map(OrganizationSlug(_)), new Writes[OrganizationSlug] {
      def writes(o: OrganizationSlug) = JsString(o.value)
    })

  private val MaxLength = 50
  private val ReservedSlugs = Set("libraries", "connections", "followers", "keeps", "tags")
  private val BeforeTruncate = Seq("[^\\w\\s-]|_" -> "", "(\\s|--)+" -> "-", "^-" -> "") map compile
  private val AfterTruncate = Seq("-$" -> "") map compile

  def isValidSlug(slug: String): Boolean = {
    slug.nonEmpty && !slug.contains(' ') && slug.length <= MaxLength
  }

  def isReservedSlug(slug: String): Boolean = {
    ReservedSlugs.contains(slug)
  }

  def generateFromName(name: String): String = {
    // taken from generateSlug() in angular/src/common/util.js
    val s1 = BeforeTruncate.foldLeft(name.toLowerCase())(replaceAll)
    val s2 = AfterTruncate.foldLeft(s1.take(MaxLength))(replaceAll)
    if (isReservedSlug(s2)) s2 + '-' else s2
  }

  private def compile(pair: (String, String)): (Pattern, String) = Pattern.compile(pair._1) -> pair._2

  private def replaceAll(s: String, op: (Pattern, String)): String = op._1.matcher(s).replaceAll(op._2)
}

object OrganizationStates extends States[Organization]