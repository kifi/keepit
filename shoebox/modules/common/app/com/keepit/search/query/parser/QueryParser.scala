package com.keepit.search.query.parser

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.BooleanClause.Occur._
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import scala.collection.mutable.ArrayBuffer

abstract class QueryParser(protected val defaultAnalyzer: Analyzer, protected val stemmingAnalyzer: Analyzer) {

  val fields: Set[String]

  def parse(queryText: CharSequence): Option[Query]

  protected def getBooleanQuery(clauses: ArrayBuffer[BooleanClause]): Option[Query] = {
    if (clauses.size == 0) {
      None // all clause words were filtered away by the analyzer.
    } else {
      val query = new BooleanQuery(true) // always disable coord
      clauses.foreach{ clause => query.add(clause) }
      Some(query)
    }
  }

  protected def getFieldQuery(field: String, queryText: String, quoted: Boolean): Option[Query] = {
    getFieldQuery(field, queryText, quoted, defaultAnalyzer)
  }

  def getStemmedFieldQuery(field: String, queryText: String): Option[Query] = {
    getFieldQuery(field, queryText, false, stemmingAnalyzer)
  }


  protected def getFieldQuery(field: String, queryText: String, quoted: Boolean, analyzer: Analyzer): Option[Query] = {
    val it = new TermIterator(field, queryText, analyzer) with Position

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
  }
}

class QueryParserException(msg: String) extends Exception(msg)

