package com.keepit.scraper

import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.util.Version
import java.io.StringReader

class SignatureBuilder(windowSize: Int = 20) extends Signature.Builder(windowSize) {

  override protected def tokenize(text: String)(addTerm: (Array[Char], Int) => Unit): Unit = {
    val ts = new StandardTokenizer(Version.LUCENE_47, new StringReader(text))
    val termAttr = ts.getAttribute(classOf[CharTermAttribute])

    try {
      ts.reset()
      while (ts.incrementToken()) addTerm(termAttr.buffer(), termAttr.length())
      ts.end()
    } finally {
      ts.close()
    }
  }

}
