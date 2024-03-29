package com.keepit.search.engine.parser

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.index.Term
import java.io.StringReader

class TermIterator(field: String, text: String, analyzer: Analyzer) extends Iterator[Term] {
  protected val ts = {
    val ts = analyzer.tokenStream(field, new StringReader(text))
    ts.reset()
    ts
  }
  private[this] val termAttr = ts.getAttribute(classOf[CharTermAttribute])
  private[this] var nextTerm: Term = readAhead
  private[this] var closed = false

  private def readAhead: Term = {
    try {
      if (ts.incrementToken()) {
        new Term(field, new String(termAttr.buffer(), 0, termAttr.length()))
      } else {
        closeTokenStream()
        null
      }
    } catch {
      case e: Throwable =>
        try { closeTokenStream() } catch { case e: Throwable => }
        throw e
    }
  }

  private def closeTokenStream(): Unit = {
    if (!closed) {
      try {
        ts.end()
      } finally {
        closed = true
        ts.close()
      }
    }
  }

  def close(): Unit = closeTokenStream()

  def hasNext() = (nextTerm != null)

  override def next(): Term = {
    val ret = nextTerm
    nextTerm = readAhead
    ret
  }
}

trait Position extends TermIterator {
  private[this] val posIncrAttr = {
    if (ts.hasAttribute(classOf[PositionIncrementAttribute])) {
      ts.getAttribute(classOf[PositionIncrementAttribute])
    } else {
      new PositionIncrementAttribute {
        private[this] var increment = 1
        def getPositionIncrement() = increment
        def setPositionIncrement(incr: Int): Unit = {
          increment = incr
        }
      }
    }
  }
  private[this] var pos = -1
  override def next(): Term = {
    pos += posIncrAttr.getPositionIncrement()
    super.next()
  }
  def position: Int = pos
}

trait Offset extends TermIterator {
  private[this] val offsetAttr = ts.getAttribute(classOf[OffsetAttribute])
  private[this] var start = -1
  private[this] var end = -1

  override def next(): Term = {
    start = offsetAttr.startOffset()
    end = offsetAttr.startOffset()
    super.next()
  }
  def startOffSet: Int = start
  def endOffset: Int = end
}

trait TermInterceptor extends TermIterator {
  override def next(): Term = {
    val term = super.next()
    process(term)
  }

  def process(term: Term): Term
}

