package com.keepit.common.net

import com.keepit.common.logging.Logging

object URI extends Logging {
  def parse(uriString: String): Option[URI] = {
    uriString.replace(" ", "%20") match {
      case URI(scheme, userInfo, host, port, path, query, fragment) =>
        Some(URI(Some(uriString), scheme, userInfo, host, port, path, query, fragment))
      case _ => 
        log.warn("Could not parse URL: %s".format(uriString))
        None // parse failed
    }
  }

  def apply(scheme: Option[String], userInfo: Option[String], host: Option[Host], port: Int, path: Option[String], query: Option[Query], fragment: Option[String]): URI = {
    apply(None, scheme, userInfo, host, port, path, query, fragment)
  }

  def apply(raw: Option[String], scheme: Option[String], userInfo: Option[String], host: Option[Host], port: Int, path: Option[String], query: Option[Query], fragment: Option[String]): URI = {
    new URI(raw, scheme, userInfo, host, port, path, query, fragment)
  }

  def unapply(uri: URI): Option[(Option[String], Option[String], Option[Host], Int, Option[String], Option[Query], Option[String])] = {
    Some((uri.scheme, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment))
  }
  def unapply(uriString: String): Option[(Option[String], Option[String], Option[Host], Int, Option[String], Option[Query], Option[String])] = {
    try {
      val uri = try {
        // preprocess illegal chars
        val preprocessed = uriString.replace("|", "%7C")

        new java.net.URI(preprocessed).normalize()
      } catch {
        case e: java.net.URISyntaxException =>
        // there may be malformed escape
        val fixedUriString = fixDoubleHash(encodeSymbols(fixMalformedEscape(uriString)))
        new java.net.URI(fixedUriString).normalize()
      }
      val scheme = normalizeScheme(Option(uri.getScheme))
      val userInfo = Option(uri.getUserInfo)
      val host = normalizeHost(Option(uri.getHost))
      val port = normalizePort(scheme, uri.getPort)
      val path = normalizePath(Option(uri.getPath))
      val query = normalizeQuery(Option(uri.getRawQuery))
      val fragment = normalizeFragment(Option(uri.getRawFragment))
      Some((scheme, userInfo, host, port, path, query, fragment))
    } catch {
      case _ => None
    }
  }

  val twoHexDigits = """\p{XDigit}\p{XDigit}""".r
  val encodedPercent = java.net.URLEncoder.encode("%", "UTF-8")
  val encodedHash = java.net.URLEncoder.encode("#", "UTF-8")

  def fixMalformedEscape(uriString: String) = {
    uriString.split("%", -1) match {
      case Array(first, rest @ _*) =>
        rest.foldLeft(first){ (str, piece) => str + twoHexDigits.findPrefixOf(piece).map(_ => "%").getOrElse(encodedPercent) + piece }
    }
  }

  def fixDoubleHash(uriString: String): String = {
    uriString.split("\\#",-1) match {
      case Array(first, rest @ _*) =>
        first + "#" + rest.mkString(encodedHash)
    }
  }

  def encodeSymbols(uriString: String): String = {
    uriString.map(_ match {
      case ch if "@$^*()[]{}|".contains(ch) => java.net.URLEncoder.encode(ch.toString, "UTF-8")
      case ch => ch
    }).mkString("")
  }

  def normalizeScheme(scheme: Option[String]) = scheme.map(_.toLowerCase)

  def normalizeHost(host: Option[String]) = {
    host.flatMap{ host =>
      host match {
        case Host(domain @ _*) => Some(Host(domain: _*))
        case host =>
          log.error("host normalization failed: [%s]".format(host))
          Some(Host(Seq(host): _*))
      }
    }
  }

  def normalizePort(scheme: Option[String], port: Int) = (scheme, port) match {
    case (Some("http"), 80) => -1
    case (Some("https"), 443) => -1
    case _ => port
  }

  val slashDotDot = """^(/\.\.)+""".r

  val defaultPage = """/(index|default)\.(html|htm|asp|aspx|php|php3|php4|phtml|cfm|cgi|jsp|jsf|jspx|jspa)$""".r

  def normalizePath(path: Option[String]) = {
    path.flatMap{ path =>
      var path2 = slashDotDot.replaceFirstIn(path.trim, "")
      defaultPage.findFirstMatchIn(path2.toLowerCase).foreach{ m =>
        val delta = path2.length - path2.toLowerCase.length // in case the case conversion changed the length
        path2 = path2.substring(0, m.start + delta) + "/"
      }
      path2 match {
        case "" => None
        case path => Some(path.replace("%7E", "~"))
      }
    }
  }

  def normalizeQuery(query: Option[String]) = {
    query.flatMap{ query =>
      query.trim match {
        case Query(params @ _*) => Some(Query(params))
        case query =>
          log.error("query normalization failed: [%s]".format(query))
          Some(Query(query))
      }
    }
  }

  def normalizeFragment(fragment: Option[String]) = {
    fragment.flatMap{ fragment =>
      fragment.trim match {
        case "" => None
        case fragment => Some(normalizeString(fragment, "fragment"))
      }
    }
  }

  def normalizeString(string: String, what: String) = {
    try {
      val decoded = java.net.URLDecoder.decode(string.replace("+", "%20"), "UTF-8") // java URLDecoder does not replace "+" with a space
      java.net.URLEncoder.encode(decoded, "UTF-8")
    } catch {
      case e: Exception =>
        log.error("%s normalization failed: [%s]".format(what, string))
        string
    }
  }
}

class URI(val raw: Option[String], val scheme: Option[String], val userInfo: Option[String], val host: Option[Host], val port: Int, val path: Option[String], val query: Option[Query], val fragment: Option[String]) {
  override def toString() = {
    val updatedPath = (path, query) match {
      case (None, None) => None
      case (None, query @ Some(_)) => Some("/")
      case (Some("/"), None) => None
      case _ => path
    }
    var uri = try {
      new java.net.URI(scheme.orNull, userInfo.orNull, host.map(_.toString).orNull, port, updatedPath.orNull, null, null).toString
    } catch {
      case e: Exception => throw new Exception("can't create java.net.URI from raw %s: scheme %s, userInfo %s, host %s, port %s, updatedPath %s".
          format(raw, scheme, userInfo, host, port, updatedPath), e)
    }
    query.foreach{ query => uri = uri + "?" + query }
    fragment.foreach{ fragment => uri = uri + "#" + fragment }
    uri
  }
}

object Host {
  def apply(host: String*) = new Host(host.toSeq)

  def unapplySeq(host: String) = {
    val domain = host.toLowerCase.split("""\.""").toSeq.reverse
    if (domain.size > 0) Some(domain)
    else None
  }
  def unapplySeq(host: Host) = Some(host.domain)
}
class Host(val domain: Seq[String]) {
  def name: String = domain.reverse.mkString(".")
  override def toString = name
}

object Query {
  def apply(params: Seq[Param]) = new Query(params)
  def apply(query: String) = new Query(Seq(new Param(query, None)))

  def unapplySeq(query: Query): Option[Seq[Param]] = Some(query.params)
  def unapplySeq(query: String): Option[Seq[Param]] = {
    var pairs = Map.empty[String, Option[String]]
    query.split("&").foreach{
      case "" =>
      case NameValuePair(name, value) => pairs += (name -> value)
    }
    Some(pairs.toSeq.sortBy(_._1).map{ case (k, v) => Param(k, v) }.toSeq)
  }

  object NameValuePair {
    def unapply(param: String): Option[(String, Option[String])] = {
      param.split("=", 2) match {
        case Array(name) => Some((URI.normalizeString(name, "parameter name"), None))
        case Array(name, value) => Some((URI.normalizeString(name, "parameter name"), Some(URI.normalizeString(value, "parameter value"))))
        case _ => Some((URI.normalizeString(param, "parameter"), None))
      }
    }
  }
}
class Query(val params: Seq[Param]) {
  override def toString() = {
    if (params.size > 0) params.mkString("&") else ""
  }

  def containsParam(name: String) = params.exists(_.name == name)
}

object Param {
  def apply(name:String, value:Option[String]) = new Param(name, value)
  def unapply(param: Param): Option[(String, Option[String])] = Some((param.name, param.value))
}
class Param(val name: String, val value: Option[String]) {
  override def toString() = {
    if (value.isDefined) (name + "=" + value.get) else name
  }
}
