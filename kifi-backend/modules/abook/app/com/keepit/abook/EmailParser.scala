package com.keepit.abook

import com.keepit.common.net.Host
import scala.util.parsing.combinator.RegexParsers

case class Email(local:LocalPart, host:Host) {
  val DOT = "."
  override val toString = s"$local@${host.domain.mkString(DOT)}"
  def toStrictString = s"${local.toStrictString}@${host.domain.mkString(DOT)}"
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
}

object Email {
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
case class LocalPart(p:Option[EComment], s:String, tag:Option[ETag], t:Option[EComment]) {
  override val toString = tag match {
    case Some(t) => s"${s.trim}${t.toString}" // todo: revisit default for tag handling
    case None => s.trim
  }
  def unparse = (p.getOrElse("") + s + tag.getOrElse("") + t.getOrElse("")).trim
  def toStrictString = s.trim // todo: revisit
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
}
