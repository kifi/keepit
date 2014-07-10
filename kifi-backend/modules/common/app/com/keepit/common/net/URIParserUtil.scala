package com.keepit.common.net

import com.keepit.common.strings.UTF8
import java.net.{ URLEncoder, URLDecoder }
import scala.collection.mutable.ArrayBuffer

object URIParserUtil {
  private[this] val controls = "\001\002\003\005\006\007\010\011\012\013\015\016\017\020\021\022\023\025\026\027\030\031\032\033\035\036\037\177"
  private[this] val space = " "
  private[this] val genDelims = ":/?#[]@"
  private[this] val subDelims = "!$&'()*+,;="
  private[this] val others = """`"^{}<>\|%"""
  private[this] val allSymbols = controls + space + genDelims + subDelims + others

  val pathReservedChars: Set[Char] = Set.empty ++ controls ++ space ++ genDelims ++ others - '@' - ':'
  val paramNameReservedChars: Set[Char] = Set.empty ++ controls ++ genDelims ++ others - '@' - ':' - '/' - '?'
  val paramValueReservedChars: Set[Char] = Set.empty ++ controls ++ genDelims ++ others - '@' - ':' - '/' - '?'
  val fragmentReservedChars: Set[Char] = Set.empty ++ controls ++ space ++ genDelims ++ others - '@' - ':' - '/' - '?' - '#'
  val encodingMap: Map[Char, String] = allSymbols.map(c => (c -> encodeChar(c))).toMap

  private[this] val _percentEncodeRe = """(\%\p{XDigit}\p{XDigit})+""".r

  def encodeChar(c: Char): String = {
    c match {
      case ' ' => "%20"
      case _ => URLEncoder.encode(c.toString, UTF8)
    }
  }

  def encode(string: String, symbols: Set[Char]): String = {
    def replaceChar(s: String, c: Char) = s.replace(c.toString, encodingMap(c))

    val tmp = if (symbols.contains('%')) replaceChar(string, '%') else string // replace % first
    symbols.foldLeft(tmp) { (s, c) =>
      if (c == '%') s else replaceChar(s, c)
    }
  }

  def encodeNonASCII(string: String, symbols: Set[Char]): String = {
    val builder = new StringBuilder()
    string.foreach { c =>
      if (symbols.contains(c)) builder ++= encodingMap(c)
      else if (c <= 255) builder += c // ASCII
      else builder ++= encodeChar(c) // non-ASCII
    }
    builder.toString
  }

  def decodePercentEncode(string: String) = {
    _percentEncodeRe.replaceAllIn(string, { m =>
      URLDecoder.decode(m.matched, UTF8).replace("""\""", """\\""").replace("$", """\$""")
    })
  }

  def normalizePort(scheme: Option[String], port: Int): Int = (scheme, port) match {
    case (Some("http"), 80) => -1
    case (Some("https"), 443) => -1
    case _ => port
  }

  def normalizePath(components: Seq[String]) = {
    var stack = List.empty[String]

    def addComponent(c: String) = {
      stack = stack match {
        case "" :: tail => c :: tail
        case s => c :: stack
      }
    }

    components.foreach {
      case ".." => if (stack.nonEmpty) stack = stack.tail
      case "." => addComponent("")
      case component => addComponent(component)
    }

    stack.reverse
  }

  def normalizePathComponent(component: String) = encode(decodePercentEncode(component), pathReservedChars)

  def normalizeParams(params: Seq[Param]): Seq[Param] = {
    var pairs = Map.empty[String, Param]
    params.filterNot(_.isEmpty).foreach { param =>
      pairs += (param.name -> param)
    }
    pairs.toSeq.sortBy(_._1).map { case (name, param) => param }.toSeq
  }

  def normalizeParamName(name: String) = encode(decodePercentEncode(name.replace('+', ' ')), paramNameReservedChars).replace(' ', '+')

  def normalizeParamValue(value: String) = encode(decodePercentEncode(value.replace('+', ' ')), paramValueReservedChars).replace(' ', '+')

  def normalizeFragment(fragment: String) = encode(decodePercentEncode(fragment), fragmentReservedChars)
}
