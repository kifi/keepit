package com.keepit.common.net

import scala.util.parsing.combinator.RegexParsers

object URIParser extends URIParserGrammar

trait URIParserGrammar extends RegexParsers {
  // this does not handle relative URI well

  override def skipWhitespace = false

  def uri: Parser[URI] = (hierarchicalUri | opaqueUri)

  def hierarchicalUri: Parser[URI] = ((scheme <~ ":").? <~ "//") ~ authority ~ (path?) ~ ("?" ~> query).? ~ ("#" ~> fragment).? ^^ {
    case scheme ~ authority ~ path ~ query ~ fragment =>
      val (userInfo, host, port) = authority
      URI(scheme, userInfo, host, URIParserUtil.normalizePort(scheme, port), path, query, fragment)
  }

  def opaqueUri: Parser[URI] = (scheme <~ ":") ~ (""".*""".r ?) ^^ {
    case scheme ~ opaquePart => URI(Option(scheme), None, None, -1, opaquePart, None, None)
  }

  def scheme: Parser[String] = """[^/?#:]+""".r ^^ (_.toLowerCase)

  def authority: Parser[(Option[String], Option[Host], Int)] = (userInfo?) ~ (host?) ~ (port?) ^^ {
    case None ~ None ~ None => (None, None, -1)
    case userInfo ~ host ~ port => (userInfo, host, port.map { port => if (port.length() > 0) port.toInt else -1 }.getOrElse(-1))
  }

  def userInfo: Parser[String] = """[^~/?#@]+""".r <~ "@"

  def host: Parser[Host] = (addressIPv6 | addressIPv4 | domain)

  def addressIPv6: Parser[Host] = """\[[^\]]*\]""".r ^^ (Host(_))

  def addressIPv4: Parser[Host] = """(\d+)\.(\d+)\.(\d+)\.(\d+)""".r ^^ (Host(_))

  def domain: Parser[Host] = rep1sep(domainPart, ".") ~ (domainTrailingDots?) ^^ {
    case host ~ None => Host(host.reverse: _*)
    case host ~ Some(dots) => Host(dots.foldLeft(host.reverse) { (names, c) => "" :: names }: _*)
  }

  def domainPart: Parser[String] = """[^~/?#@:\. ]+""".r ^^ (_.toLowerCase)

  def domainTrailingDots: Parser[String] = """\.+""".r

  def port: Parser[String] = ":" ~> """\d+""".r

  def path: Parser[String] = "/" ~> (repsep(pathComponent, "/")) ^^ { components => "/" + normalizePath(components).mkString("/") }

  def pathComponent: Parser[String] = """[^/?#]*""".r ^^ (normalizePathComponent(_))

  def query: Parser[Query] = repsep(param, "&") ^^ { params => Query(normalizeParams(params)) }

  def param: Parser[Param] = """[^#&=]*""".r ~ ("=" ~> """[^#&]*""".r).? ^^ {
    case name ~ value => Param(normalizeParamName(name), value.map(normalizeParamValue))
  }

  def fragment: Parser[String] = """.*""".r ^^ { fragment => normalizeFragment(fragment) }

  def normalizePath(components: Seq[String]): Seq[String] = URIParserUtil.normalizePath(components)
  def normalizePathComponent(component: String): String = URIParserUtil.normalizePathComponent(component)
  def normalizeParams(params: Seq[Param]): Seq[Param] = URIParserUtil.normalizeParams(params)
  def normalizeParamName(name: String): String = URIParserUtil.normalizeParamName(name)
  def normalizeParamValue(value: String): String = URIParserUtil.normalizeParamValue(value)
  def normalizeFragment(fragment: String): String = URIParserUtil.normalizeFragment(fragment)
}

