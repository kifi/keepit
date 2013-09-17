package com.keepit.scraper.extractor

import com.keepit.search.util.AhoCorasick
import org.apache.tika.sax.ContentHandlerDecorator
import org.xml.sax.ContentHandler

class KeywordValidatorContentHandler(keywordCandidates: Seq[String], handler: ContentHandler) extends ContentHandlerDecorator(handler) {

  private[this] val matcher = new AhoCorasick[Char, (String, Int)](keywordCandidates.zipWithIndex.map{ case (k, i) => (s" ${k.toLowerCase} ".toCharArray().toSeq, (k, i)) })

  private[this] var validatedKeywords = Set.empty[(String, Int)]
  private[this] val onMatch: (Int,(String, Int))=>Unit = { (pos: Int, k: (String, Int)) => validatedKeywords += k }

  private[this] var state = matcher.initialState
  private[this] var lastChar = ' '

  override def startDocument(): Unit = {
    super.startDocument()
    state = matcher.next(' ', state)
  }

  override def endDocument(): Unit = {
    super.endDocument()
    state = matcher.next(' ', state)
    state.check(0, onMatch) // dummy position
  }

  override def characters(ch: Array[Char], start: Int, length: Int): Unit = {
    super.characters(ch, start, length)

    var i = start
    val end = start + length
    while (i < end) {
      var char = ch(i).toLower
      if (!char.isLetterOrDigit) char = ' '
      if (char != ' ' || lastChar != ' ') {
        lastChar = char
        state = matcher.next(char, state)
        if (char == ' ') state.check(0, onMatch) // dummy position
      }
      i += 1
    }
  }

  def keywords: Seq[String] = validatedKeywords.toSeq.sortBy(_._2).map(_._1)
}

