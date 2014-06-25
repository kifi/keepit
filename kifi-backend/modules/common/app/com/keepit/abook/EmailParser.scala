package com.keepit.abook

import com.keepit.common.mail.EmailAddress
import com.keepit.common.net.Host
import scala.util.parsing.combinator.RegexParsers

case class ParsedEmailAddress(local: LocalPart, host: Host) {
  val domain = host.domain.mkString(".").trim
  override val toString = s"$local@$domain"
  def toStrictString = s"${local.toStrictString}@$domain"
  def toDbgString = s"[ParsedEmailAddress(${local.toDbgString} host=${host})]"
  override def hashCode = toString.hashCode
  override def equals(o: Any) = {
    o match {
      case e: ParsedEmailAddress => (toString == e.toString)
      case _ => false
    }
  }
  def strictEquals(o:Any) = {
    o match {
      case e: ParsedEmailAddress => (toStrictString == e.toStrictString)
      case _ => false
    }
  }

  def isKifi: Boolean = domain == ParsedEmailAddress.FORTYTWO_DOMAIN || domain == ParsedEmailAddress.KIFI_DOMAIN
  def isTest: Boolean = {
    (isKifi || domain == "tfbnw.net" || domain == "mailinator.com") &&
      local.tags.exists { tag => tag.t.startsWith("test") || tag.t.startsWith("utest") }
  }
  def isAutoGen: Boolean = isKifi && local.tags.exists(_.t.startsWith("autogen"))
  def isTagged(tag: String): Boolean = local.tags.exists(_.t == tag)
}

object ParsedEmailAddress {
  val FORTYTWO_DOMAIN = "42go.com"
  val KIFI_DOMAIN = "kifi.com"

  def apply(s:String) = {
    EmailParser.parse[ParsedEmailAddress](EmailParser.email, s).getOrElse(throw new IllegalArgumentException(s"Cannot parse $s"))
  }

  implicit def toEmailAddress(email: ParsedEmailAddress): EmailAddress = EmailAddress(email.toString)
}

case class EComment(c: String) {
  override val toString = s"($c)"
}
case class ETag(t: String) {
  override val toString = s"+$t"
}

case class LocalPart(p: Option[EComment], s: String, tags: Seq[ETag], t: Option[EComment]) {
  val localName = s.trim.toLowerCase  // mysql is case-insensitive
  override val toString = s"${localName}${tags.mkString("")}"
  def unparse = (p.getOrElse("") + localName + tags.mkString("") + t.getOrElse("")).trim
  def toStrictString = localName
  def toDbgString = s"[LocalPart($p,$s,$tags,$t)]"
}

case class EmailParserConfig(ignoreTags: Boolean = false, ignoreComments: Boolean = true) // todo: uptake config

object EmailParser extends RegexParsers { // rudimentary; also see @URIParser
  def parseOpt(s: String): Option[ParsedEmailAddress] = parseAll(EmailParser.email, s).asOpt

  override def skipWhitespace = false
  def email: Parser[ParsedEmailAddress] = localPart ~ "@" ~ host ^^ {
    case localPart ~ "@" ~ host => ParsedEmailAddress(localPart, host)
  }
  def comment: Parser[EComment] = "(" ~> commentBody <~ ")" ^^ { EComment(_) }
  def commentBody: Parser[String] = """[^~/?#()+@]+""".r
  def tag: Parser[ETag] = "+" ~> """[^~/?#()+@]+""".r ^^ { ETag(_) }
  def localPart: Parser[LocalPart] = (comment?) ~ """[^~/?#()+@]+""".r ~ (tag*) ~ (comment?) ^^ {
    case p ~ s ~ tags ~ t => LocalPart(p, s, tags, t)
  }
  def host: Parser[Host] = (comment?) ~ rep1sep(domainPart, ".") ~ (comment?) ^^ {
    case p ~ domainList ~ t => new Host(domainList) // discard comment in domain
  }
  def domainPart: Parser[String] = """[^~/?#@:\.]+""".r ^^ (_.toLowerCase)
  // todo: quotes, dots, nameprep

  implicit class Wrapper(val res:ParseResult[ParsedEmailAddress]) extends AnyVal {
    def asOpt: Option[ParsedEmailAddress] = if (res.successful) Some(res.get) else None
  }
}
