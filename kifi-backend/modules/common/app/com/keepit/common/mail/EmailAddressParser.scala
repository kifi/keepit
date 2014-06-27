package com.keepit.common.mail

import com.keepit.common.net.Host
import scala.util.parsing.combinator.RegexParsers

protected case class Comment(c: String) {
  override val toString = s"($c)"
}
protected case class Tag(t: String) {
  override val toString = s"+$t"
}
protected case class LocalPart(p: Option[Comment], s: String, tags: Seq[Tag], t: Option[Comment]) { // TODO: eliminate comments (not part of address)
  val localName = s.trim.toLowerCase  // local part case insensitive (kifi policy)
  override val toString = s"${localName}${tags.mkString("")}"
  def unparse = (p.getOrElse("") + localName + tags.mkString("") + t.getOrElse("")).trim
  def toStrictString = localName
  def toDbgString = s"[LocalPart($p,$s,$tags,$t)]"
}

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

  def hasTag(tag: String): Boolean = local.tags.exists(_.t == tag)
  def hasTagPrefix(prefix: String): Boolean = local.tags.exists(_.t.startsWith(prefix))
}

object ParsedEmailAddress {
  def apply(s: String) = {
    EmailAddressParser.parseOpt(s).getOrElse(throw new IllegalArgumentException(s"Cannot parse $s"))
  }

  implicit def toEmailAddress(addr: ParsedEmailAddress): EmailAddress = EmailAddress(addr.toString)
}

object EmailAddressParser extends RegexParsers { // rudimentary; also see @URIParser
  def parseOpt(s: String): Option[ParsedEmailAddress] = Option(parseAll(email, s).getOrElse(null))

  override def skipWhitespace = false

  def email: Parser[ParsedEmailAddress] = (emailAddress | emailField)
  def emailField: Parser[ParsedEmailAddress] = withSpaces ~ "<" ~ emailAddress ~ ">" ^^ {
    case name ~ "<" ~ addr ~ ">" => addr // discard name for now
  }
  def emailAddress: Parser[ParsedEmailAddress] = localPart ~ "@" ~ host ^^ {
    case localPart ~ "@" ~ host => ParsedEmailAddress(localPart, host)
  }

  // more restrictive than rfc
  val sanitized  = """[^"~/?#()+@<>. ]+""".r
  val withSpaces = """[^"~/?#()+<>@]+""".r
  val DQ = '\"'

  def comment: Parser[Comment] = "(" ~> commentBody <~ ")" ^^ { Comment(_) }
  def commentBody: Parser[String] = sanitized
  def tag: Parser[Tag] = "+" ~> sanitized ^^ { Tag(_) }
  def localPart: Parser[LocalPart] = (quoted | obsLocalPart)

  def obsLocalPart: Parser[LocalPart] = (comment?) ~ sanitized ~ (tag*) ~ (comment?) ^^ { // todo: factor out comment
    case p ~ s ~ tags ~ t => LocalPart(p, s, tags, t)
  }
  def quoted: Parser[LocalPart] = "\"" ~ withSpaces ~ "\"" ^^ {
    case open ~ local ~ close => LocalPart(None, s"$DQ$local$DQ", Seq.empty, None)
  }
  def host: Parser[Host] = (comment?) ~ rep1sep(domainPart, ".") ~ (comment?) ^^ {
    case p ~ domainList ~ t => new Host(domainList) // discard comment in domain
  }
  def domainPart: Parser[String] = sanitized ^^ (_.toLowerCase)
  // todo: quotes (full support), dots, nameprep?
}
