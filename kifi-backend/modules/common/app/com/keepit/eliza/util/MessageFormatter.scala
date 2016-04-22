package com.keepit.eliza.util

import java.net.URLDecoder

import com.keepit.common.logging.Logging

import scala.util.matching.Regex.Match
import scala.util.{ Failure, Success, Try }

sealed abstract class MessageSegment(val kind: String, val txt: String) //for use in templates since you can't match on type (it seems)
case class TextLookHereSegment(override val txt: String, pageText: String) extends MessageSegment("tlh", txt)
case class ImageLookHereSegment(override val txt: String, imgUrl: String) extends MessageSegment("ilh", txt)
case class TextSegment(override val txt: String) extends MessageSegment("txt", txt)

object MessageFormatter extends Logging {

  private[this] val lookHereRe = """\[([^\]\\]*(?:\\[\]\\][^\]\\]*)*)\]\(x-kifi-sel:([^\)\\]*(?:\\[\)\\][^\)\\]*)*)\)""".r
  private[this] val escapedBackslashOrRightBracketRe = """\\([\]\\])""".r
  private[this] val escapedBackslashOrRightParenRe = """\\([\)\\])""".r

  /**
   * Formats [[com.keepit.eliza.model.ElizaMessage.messageText]] (in a markdown-based format) as plain text.
   * Removes "look here" links, preserving only the link text.
   */
  def toText(messageText: String): String = {
    lookHereRe.replaceAllIn(messageText, (m: Match) => m.group(1))
  }

  /**
   * Parses message segments from [[com.keepit.eliza.model.ElizaMessage.messageText]] (in a markdown-based format)
   */
  def parseMessageSegments(msg: String): Seq[MessageSegment] = Try {

    def parseSegment(m: Match) = {
      val segments = m.group(2).split('|')
      val kind = segments.head
      val payload = escapedBackslashOrRightParenRe.replaceAllIn(URLDecoder.decode(segments.last, "UTF-8"), "$1")
      val text = escapedBackslashOrRightBracketRe.replaceAllIn(m.group(1), "$1")
      kind match {
        case "i" => ImageLookHereSegment(text, payload)
        case "r" | _ => TextLookHereSegment(text, payload)
      }
    }

    val matches = try {
      lookHereRe.findAllMatchIn(msg)
    } catch {
      // Parsing may sometimes cause stack overflow errors (typically when using alternations)
      case t: StackOverflowError => throw new Exception(s"Exception during parsing of message $msg", t)
    }
    val (position, segments) = matches.foldLeft((0, Seq[MessageSegment]())) { (acc, m) =>
      val (currPos, seq) = acc
      val lookHereSegment = parseSegment(m)
      (m.end, if (m.start > currPos) seq :+ TextSegment(msg.substring(currPos, m.start)) :+ lookHereSegment
      else seq :+ lookHereSegment)
    }
    val ending = TextSegment(msg.substring(position))
    if (ending.txt.length > 0) segments :+ ending else segments
  } match {
    case Success(segments) => segments
    case Failure(ex) =>
      log.error(s"Couldn't parse message: $msg. ${ex.getMessage}")
      Seq(TextSegment(msg))
  }
}
