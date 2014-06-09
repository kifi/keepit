package com.keepit.abook

import com.keepit.common.net.Host
import scala.util.parsing.combinator.RegexParsers

case class Email(local:LocalPart, host:Host) {
  val domain = host.domain.mkString(".").trim
  override val toString = s"$local@$domain"
  def toStrictString = s"${local.toStrictString}@$domain"
  def toDbgString = s"[Email(${local.toDbgString} host=${host})]"
  override def hashCode = toString.hashCode
  override def equals(o: Any) = {
    o match {
      case e:Email => (toString == e.toString)
      case _ => false
    }
  }
  def strictEquals(o:Any) = {
    o match {
      case e:Email => (toStrictString == e.toStrictString)
      case _ => false
    }
  }

  def isKifi = (domain == Email.FORTYTWO_DOMAIN) || (domain == Email.KIFI_DOMAIN)
  def isTest = isFake || isAutoGen
  def isFake = isKifi && ((local.tag exists (tag => tag.t.startsWith(ETag.TEST) || tag.t.startsWith(ETag.UTEST))) || host.name == "tfbnw.net")
  def isAutoGen = isKifi && (local.tag exists (tag => tag.t.startsWith(ETag.AUTOGEN)))
}

object Email {

  val FORTYTWO_DOMAIN = "42go.com" // todo: kifi.com
  val KIFI_DOMAIN = "kifi.com"

  def apply(s:String) = {
    EmailParser.parse[Email](EmailParser.email, s).getOrElse(throw new IllegalArgumentException(s"Cannot parse $s"))
  }
}

case class EComment(c:String) {
  override val toString = s"($c)"
}
case class ETag(t:String) {
  override val toString = s"+$t"
}
object ETag {
  val TEST    = "test"
  val UTEST = "utest"
  val AUTOGEN = "autogen"
}

case class LocalPart(p:Option[EComment], s:String, tag:Option[ETag], t:Option[EComment]) {
  val localName = s.trim.toLowerCase  // mysql is case-insensitive
  override val toString = tag match { // todo: revisit default for tag handling
    case Some(t) => s"${localName}${t.toString}"
    case None => localName
  }
  def unparse = (p.getOrElse("") + localName + tag.getOrElse("") + t.getOrElse("")).trim
  def toStrictString = localName
  def toDbgString = s"[LocalPart($p,$s,$tag,$t)]"
}

case class EmailParserConfig(ignoreTags:Boolean = false, ignoreComments:Boolean = true) // todo: uptake config

object EmailParser extends RegexParsers { // rudimentary; also see @URIParser
  override def skipWhitespace = false
  def email:Parser[Email] = localPart ~ "@" ~ host ^^ {
    case localPart ~ "@" ~ host => Email(localPart, host)
  }
  def comment:Parser[EComment] = "(" ~> commentBody <~ ")" ^^ { EComment(_) }
  def commentBody:Parser[String] = """[^~/?#()+@]+""".r
  def tag:Parser[ETag] = "+" ~> """[^~/?#()+@]+""".r ^^ { ETag(_) }
  def localPart:Parser[LocalPart] = (comment?) ~ """[^~/?#()+@]+""".r ~ (tag?) ~ (comment?) ^^ {
    case p ~ s ~ tag ~ t => LocalPart(p, s, tag, t)
  }
  def host:Parser[Host] = (comment?) ~ rep1sep(domainPart, ".") ~ (comment?) ^^ {
    case p ~ domainList ~ t => new Host(domainList) // discard comment in domain
  }
  def domainPart:Parser[String] = """[^~/?#@:\.]+""".r ^^ (_.toLowerCase)
  // todo: quotes, dots, nameprep

  implicit class Wrapper(val res:ParseResult[Email]) extends AnyVal {
    def asOpt:Option[Email] = if (res.successful) Some(res.get) else None
  }
}

object EmailParserUtils {

  def parseOpt(s:String):Option[Email] = EmailParser.parseAll(EmailParser.email, s).asOpt

  def isKifiEmail(s:String) = parseOpt(s) exists { _.isKifi }
  def isTestEmail(s:String) = parseOpt(s) exists { _.isTest }
  def isFakeEmail(s:String) = parseOpt(s) exists { _.isFake }
  def isAutoGenEmail(s:String) = parseOpt(s) exists { _.isAutoGen }

}
