package com.keepit.search.query

import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.query.parser.QueryParser
import com.keepit.search.query.parser.DefaultSyntax
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.index.Term
import org.apache.lucene.util.Version
import java.util.{List => JList}

object QueryHash {
  def apply(userId: Id[User], queryText: String, analyzer: Analyzer): Long = {
    val hash = new StringHash64(userId.id)

    if (queryText != null && queryText.trim.length != 0) {
      // use the minimum parser to avoid expansions etc.
      val parser = new QueryParser(analyzer, analyzer) with DefaultSyntax
      parser.parse(queryText).foreach{ query => hash.update(query.toString) }
    }

    hash.get
  }
}

class StringHash64(seed: Long) {
  private[this] var h1 = (seed + 0x123456L)
  private[this] var h2 = reverse(seed + 0xABCDEFL)

  def get = (h1 + h2)

  def update(str: String) {
    str.foreach{ c => update(c) }
  }

  def update(c: Char) {
    h1 = h1 * rotate(h1, (c.toInt & 0x3F)) + 12345L
    h2 = h2 * c.toLong + 6789L
  }

  private[this] def reverse(v: Long): Long = {
    var x = v
    x = (((x & 0xAAAAAAAAAAAAAAAAL) >>> 1) | ((x & 0x5555555555555555L) << 1))
    x = (((x & 0xCCCCCCCCCCCCCCCCL) >>> 2) | ((x & 0x3333333333333333L) << 2))
    x = (((x & 0xF0F0F0F0F0F0F0F0L) >>> 4) | ((x & 0X0F0F0F0F0F0F0F0FL) << 4))
    x = (((x & 0xFF00FF00FF00FF00L) >>> 8) | ((x & 0x00FF00FF00FF00FFL) << 8))
    x = (((x & 0xFFFF0000FFFF0000L) >>> 16)| ((x & 0x0000FFFF0000FFFFL) << 16))
    ((x >>> 32) | (x << 32))
  }
  private[this] def rotate(v: Long, n: Int): Long = (v << n) | (v >>> (64 - n))
}
