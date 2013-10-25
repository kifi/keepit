package com.keepit.search.user

import com.keepit.search.index.Analyzer
import com.keepit.search.query.parser.QueryParser
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.document.Field
import com.keepit.search.index.Indexable
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.PrefixQuery
import com.keepit.search.query.parser.QuerySpec

class UserQueryParser(
  analyzer: Analyzer,
  stemmingAnalyzer: Analyzer
) extends QueryParser(analyzer, stemmingAnalyzer) {
  
  override val fields: Set[String] = Set.empty[String]
  
  override def parse(queryText: CharSequence): Option[Query] = {
    
    def maybeEmailAddress(queryText: CharSequence) = queryText.toString().contains('@')
    
    if (queryText == null) None
    else {
      if (maybeEmailAddress(queryText)) Some(genEmailQuery(queryText))
      else Some(genNameQuery(queryText))
    }
  }
  
  private def genEmailQuery(queryText: CharSequence): Query = {
    val bq = new BooleanQuery
    val tq = new TermQuery(new Term(UserIndexer.EMAILS_FIELD, queryText.toString))
    bq.add(tq, Occur.MUST)
    bq
  }
  
  private def genNameQuery(queryText: CharSequence): Query = {
    val bq = new BooleanQuery
    val tq = new PrefixQuery(new Term(UserIndexer.FULLNAME_FIELD, queryText.toString))
    bq.add(tq, Occur.MUST)
    bq
  }
  
  override protected def buildQuery(querySpecList: List[QuerySpec]): Option[Query] = ???
  
}
