package com.keepit.search.line

import org.apache.lucene.index.IndexReader

class CondNode(source: LineQuery, cond: LineQuery, reader: IndexReader) extends LineQuery(1.0f) {

  override def fetchDoc(targetDoc: Int) = {
    val doc = source.fetchDoc(targetDoc)

    // NOTE: we don't know if this is a hit or not at this point
    if (cond.curDoc < doc) cond.fetchDoc(doc)

    curPos = -1
    curLine = -1
    curDoc = doc
    curDoc
  }

  private def isValid(line: Int) = {
    if (cond.curDoc == curDoc) {
      if (cond.curLine < line) cond.fetchLine(line)
      cond.curLine == line
    } else false
  }

  override def fetchLine(targetLine: Int) = {
    var line = source.fetchLine(targetLine)
    while (line < LineQuery.NO_MORE_LINES && !isValid(line)) {
      line = source.fetchLine(targetLine)
    }
    curPos = -1
    curLine = line
    curLine
  }

  override def computeScore = source.score
}
