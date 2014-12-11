package com.keepit.search.index.message

import com.keepit.search.query.parser.{ QueryParser, DefaultSyntax, QueryParserException }
import com.keepit.search.query.QueryUtil.copy

import org.apache.lucene.search.{ Query, TermQuery, PhraseQuery, DisjunctionMaxQuery }
import org.apache.lucene.analysis.Analyzer

class MessageQueryParser(
    analyzer: Analyzer,
    stemmingAnalyzer: Analyzer) extends QueryParser(analyzer, stemmingAnalyzer) with DefaultSyntax {

  def copyFieldQuery(query: Query, field: String) = {
    query match {
      case null => null
      case query: TermQuery => copy(query, field)
      case query: PhraseQuery => copy(query, field)
      case _ => throw new QueryParserException(s"failed to copy query: ${query.toString}")
    }
  }

  override def getFieldQuery(field: String, queryText: String, quoted: Boolean): Option[Query] = {
    val disjunct = new DisjunctionMaxQuery(0.5f)

    super.getFieldQuery(ThreadIndexFields.contentField, queryText, quoted).foreach { query =>
      disjunct.add(query)
      disjunct.add(copyFieldQuery(query, ThreadIndexFields.titleField))
      disjunct.add(copyFieldQuery(query, ThreadIndexFields.participantNameField))
      disjunct.add(copyFieldQuery(query, ThreadIndexFields.urlKeywordField))
    }

    getStemmedFieldQuery(ThreadIndexFields.contentStemmedField, queryText).foreach { query =>
      if (!quoted) {
        disjunct.add(query)
        disjunct.add(copyFieldQuery(query, ThreadIndexFields.titleStemmedField))
      }
    }
    if (disjunct.iterator().hasNext) Some(disjunct) else None

  }

}

