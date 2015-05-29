package com.keepit.model

import java.net.URLEncoder
import java.util.regex.Pattern

import com.keepit.common.crypto.ModelWithPublicId
import com.keepit.common.db._
import com.keepit.common.strings._
import com.keepit.common.time._
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import play.api.libs.json._

import scala.util.Random

case class Organization(
                    id: Option[Id[Organization]] = None,
                    createdAt: DateTime = currentDateTime,
                    updatedAt: DateTime = currentDateTime,
                    name: String,
                    ownerId: Id[User],
                    // Organization visibility is not the same as LibraryVisibility (we are already modifying a library to be "organization visible"
                    // Do we even want an organization to have a visibility at all? We can instead just only show what is visible to a person based on their access.
                    visibility: OrganizationVisibility,
                    description: Option[String] = None,
                    slug: OrganizationSlug,
                    // Should we allow actual hex codes instead of a predefined few?
                    color: Option[OrganizationColor] = None,
                    state: State[Organization] = OrganizationStates.ACTIVE,
                    seq: SequenceNumber[Organization] = SequenceNumber.ZERO,
                    // All organizations will be user_created, so this is unnecessary?
                    kind: OrganizationKind = OrganizationKind.USER_CREATED,
                    // Replace with externalId, add "with ModelWithExternalId[Organization]"?
                    universalLink: String = RandomStringUtils.randomAlphanumeric(40),
                    memberCount: Int,
                    // Remove this?
                    lastKept: Option[DateTime] = None,
                    // Rename to libraryCount?
                    keepCount: Int = 0,
                    /* Instead of a field, does another table make more sense?
                     * This way we are not adding everything to a single table, but can keep these ideas(library, permissions) separate
                    */
                    whoCanInvite: Option[LibraryInvitePermissions] = None
                         ) extends ModelWithPublicId[Organization] with ModelWithState[Organization] with ModelWithSeqNumber[Organization] {

  override def withId(id: Id[Organization]): Organization = this.copy(id = Some(id))

  override def withUpdateTime(now: DateTime): Organization = this.copy(updatedAt = now)
}

// Remove this?
sealed abstract class OrganizationKind(val value: String, val priority: Int) extends Ordered[OrganizationKind] {
  override def compare(other: OrganizationKind): Int = this.priority compare other.priority
}

object OrganizationKind {
  final case object SYSTEM_MAIN extends OrganizationKind("system_main", 0)
  final case object SYSTEM_SECRET extends OrganizationKind("system_secret", 1)
  final case object SYSTEM_PERSONA extends OrganizationKind("system_persona", 2)
  final case object USER_CREATED extends OrganizationKind("user_created", 2)

  implicit def format[T]: Format[OrganizationKind] =
    Format(__.read[String].map(OrganizationKind(_)), new Writes[OrganizationKind] { def writes(o: OrganizationKind) = JsString(o.value) })

  def apply(str: String) = {
    str match {
      case SYSTEM_MAIN.value => SYSTEM_MAIN
      case SYSTEM_SECRET.value => SYSTEM_SECRET
      case SYSTEM_PERSONA.value => SYSTEM_PERSONA
      case USER_CREATED.value => USER_CREATED
    }
  }
}

case class OrganizationSlug(value: String) {
  def urlEncoded: String = URLEncoder.encode(value, UTF8)
}

object OrganizationSlug {
  implicit def format: Format[OrganizationSlug] =
    Format(__.read[String].map(OrganizationSlug(_)), new Writes[OrganizationSlug] { def writes(o: OrganizationSlug) = JsString(o.value) })

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

sealed abstract class OrganizationVisibility(val value: String)

object OrganizationVisibility {
  final case object PUBLISHED extends OrganizationVisibility("published") // published library, is discoverable
  final case object SECRET extends OrganizationVisibility("secret") // secret, not discoverable

  implicit def format[T]: Format[OrganizationVisibility] =
    Format(__.read[String].map(OrganizationVisibility(_)), new Writes[OrganizationVisibility] { def writes(o: OrganizationVisibility) = JsString(o.value) })

  def apply(str: String) = {
    str match {
      case PUBLISHED.value => PUBLISHED
      case SECRET.value => SECRET
    }
  }
}

object OrganizationStates extends States[Organization]

sealed abstract class OrganizationColor(val hex: String)
object OrganizationColor {

  implicit def format[T]: Format[OrganizationColor] =
    Format(__.read[String].map(OrganizationColor(_)), new Writes[OrganizationColor] { def writes(o: OrganizationColor) = JsString(o.hex) })

  final case object BLUE extends OrganizationColor("#447ab7")
  final case object SKY_BLUE extends OrganizationColor("#5ab7e7")
  final case object GREEN extends OrganizationColor("#4fc49e")
  final case object ORANGE extends OrganizationColor("#f99457")
  final case object RED extends OrganizationColor("#dd5c60")
  final case object MAGENTA extends OrganizationColor("#c16c9e")
  final case object PURPLE extends OrganizationColor("#9166ac")

  def apply(str: String): OrganizationColor = {
    str match {
      case "blue" | BLUE.hex => BLUE
      case "sky_blue" | SKY_BLUE.hex => SKY_BLUE
      case "green" | GREEN.hex => GREEN
      case "orange" | ORANGE.hex => ORANGE
      case "red" | RED.hex => RED
      case "magenta" | MAGENTA.hex => MAGENTA
      case "purple" | PURPLE.hex => PURPLE
    }
  }

  val AllColors: Seq[OrganizationColor] = Seq(BLUE, SKY_BLUE, GREEN, ORANGE, RED, MAGENTA, PURPLE)

  private lazy val rnd = new Random

  def pickRandomLibraryColor(): OrganizationColor = {
    AllColors(rnd.nextInt(AllColors.size))
  }
}