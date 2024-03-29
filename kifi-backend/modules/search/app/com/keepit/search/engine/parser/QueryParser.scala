package com.keepit.search.engine.parser

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import scala.collection.mutable.ArrayBuffer

abstract class QueryParser(protected val defaultAnalyzer: Analyzer, protected val defaultStemmingAnalyzer: Analyzer) {

  val fields: Set[String]

  def parse(queryText: CharSequence): Option[Query] = parseSpecs(queryText).flatMap(buildQuery)

  protected def getBooleanQuery(clauses: ArrayBuffer[BooleanClause]): Option[Query] = {
    if (clauses.size == 0) {
      None // all clause words were filtered away by the analyzer.
    } else {
      val query = new BooleanQuery(true) // always disable coord
      clauses.foreach { clause => query.add(clause) }
      Some(query)
    }
  }

  protected def getFieldQuery(field: String, queryText: String, quoted: Boolean, trailing: Boolean): Option[Query] = {
    getFieldQuery(field, queryText, quoted, trailing, defaultAnalyzer)
  }

  protected def getStemmedFieldQuery(field: String, queryText: String, trailing: Boolean): Option[Query] = {
    getFieldQuery(field, queryText, false, trailing, defaultStemmingAnalyzer)
  }

  protected def getFieldQuery(field: String, queryText: String, quoted: Boolean, trailing: Boolean, analyzer: Analyzer): Option[Query] = {
    val it = new TermIterator(field, queryText, analyzer) with Position
    try {
      if (it.hasNext) {
        var term = it.next()
        var pos = it.position

        val query = if (!it.hasNext) {
          new TermQuery(term)
        } else {
          val phraseQuery = new PhraseQuery()
          phraseQuery.add(term, pos)
          while (it.hasNext) {
            term = it.next()
            pos = it.position
            phraseQuery.add(term, pos)
          }
          phraseQuery
        }
        Some(query)
      } else {
        None
      }
    } finally {
      it.close()
    }
  }

  protected def buildQuery(querySpecList: List[QuerySpec]): Option[Query]

  protected def parseSpecs(queryText: CharSequence): Option[List[QuerySpec]]
}

case class QuerySpec(occur: Occur, field: String, term: String, quoted: Boolean, trailing: Boolean)

class QueryParserException(msg: String) extends Exception(msg)

