package com.keepit.search.index.user

import com.keepit.search.index.Analyzer
import com.keepit.search.query.parser.QueryParser
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.PrefixQuery
import com.keepit.search.query.parser.QuerySpec
import java.io.StringReader
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import com.keepit.typeahead.PrefixFilter

class UserQueryParser(
    analyzer: Analyzer) extends QueryParser(analyzer, analyzer) {

  override val fields: Set[String] = Set.empty[String]

  def parseWithUserExperimentConstrains(queryText: CharSequence, exps: Seq[String], useLucenePrefixQuery: Boolean = false): Option[Query] = {

    if (queryText == null) None
    else {
      val bq = if (maybeEmailAddress(queryText)) genEmailQuery(queryText)
      else if (useLucenePrefixQuery) genNameQuery(queryText)
      else genPrefixNameQuery(queryText)

      bq.foreach(q => addUserExperimentConstrains(q, exps))
      bq
    }
  }

  override def parse(queryText: CharSequence): Option[Query] = {

    if (queryText == null) None
    else {
      if (maybeEmailAddress(queryText)) genEmailQuery(queryText)
      else genNameQuery(queryText)
    }
  }

  private def maybeEmailAddress(queryText: CharSequence) = queryText.toString().contains('@')

  private def genEmailQuery(queryText: CharSequence): Option[BooleanQuery] = {
    if (queryText == null) None
    else {
      val bq = new BooleanQuery
      val tq = new TermQuery(new Term(UserIndexer.EMAILS_FIELD, queryText.toString.toLowerCase))
      bq.add(tq, Occur.MUST)
      Some(bq)
    }

  }

  private def genNameQuery(queryText: CharSequence): Option[BooleanQuery] = {

    val bq = new BooleanQuery
    val ts = analyzer.tokenStream(UserIndexer.FULLNAME_FIELD, new StringReader(queryText.toString))
    try {
      ts.reset()

      val termAttr = ts.getAttribute(classOf[CharTermAttribute])

      while (ts.incrementToken) {
        val tq = new PrefixQuery(new Term(UserIndexer.FULLNAME_FIELD, new String(termAttr.buffer(), 0, termAttr.length())))
        bq.add(tq, Occur.MUST)
      }
      ts.end()
    } finally {
      ts.close()
    }

    if (bq.clauses.size > 0) Some(bq) else None
  }

  private def genPrefixNameQuery(queryText: CharSequence): Option[BooleanQuery] = {
    val bq = new BooleanQuery
    PrefixFilter.tokenize(queryText.toString).foreach { q =>
      val tq = new TermQuery(new Term(UserIndexer.PREFIX_FIELD, q.take(UserIndexer.PREFIX_MAX_LEN)))
      bq.add(tq, Occur.MUST)
    }
    if (bq.clauses.size > 0) Some(bq) else None
  }

  private def addUserExperimentConstrains(bq: BooleanQuery, exps: Seq[String]) = {
    exps.foreach { exp =>
      val tq = new TermQuery(new Term(UserIndexer.USER_EXPERIMENTS, exp))
      bq.add(tq, Occur.MUST_NOT)
    }
  }

  override protected def buildQuery(querySpecList: List[QuerySpec]): Option[Query] = ???

}
