package com.keepit.search.query.parser

import com.keepit.classify.Domain
import com.keepit.search.query.QueryUtil._
import com.keepit.search.query.MediaQuery
import com.keepit.search.query.SiteQuery
import com.keepit.search.query.TextQuery
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanClause.Occur._
import org.apache.lucene.search.DisjunctionMaxQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import scala.collection.mutable.ArrayBuffer

trait QueryExpansion extends QueryParser {

  val siteBoost: Float
  val concatBoost: Float

  val textQueries: ArrayBuffer[TextQuery] = ArrayBuffer()

  override def getFieldQuery(field: String, queryText: String, quoted: Boolean): Option[Query] = {
    field.toLowerCase match {
      case "site" => getSiteQuery(queryText)
      case "media" => Option(MediaQuery(queryText))
      case _ => getTextQuery(queryText, quoted)
    }
  }

  protected def getSiteQuery(domain: String): Option[Query] = if (domain != null) Option(SiteQuery(domain)) else None

  protected def getTextQuery(queryText: String, quoted: Boolean): Option[Query] = {
    def copyFieldQuery(query:Query, field: String) = {
      query match {
        case null => null
        case query: TermQuery => copy(query, field)
        case query: PhraseQuery => copy(query, field)
        case _ => throw new QueryParserException(s"failed to copy query: ${query.toString}")
      }
    }

    def addSiteQuery(baseQuery: TextQuery, queryText: String, query: Query) {
      (if (Domain.isValid(queryText)) Option(SiteQuery(queryText)) else None).orElse{
        query match {
          case query: TermQuery => Option(copyFieldQuery(query, "site_keywords"))
          case _ => None
        }
      }.foreach{ q =>
        baseQuery.addRegularQuery(q, siteBoost)
      }
    }

    def isNumericTermQuery(query: Query): Boolean = query match {
      case q: TermQuery => q.getTerm.text.forall(Character.isDigit)
      case _ => false
    }

    val textQuery = new TextQuery
    textQueries += textQuery

    super.getFieldQuery("t", queryText, quoted).foreach{ query =>
      textQuery.terms = extractTerms(query)
      textQuery.addRegularQuery(query)
      textQuery.addRegularQuery(copyFieldQuery(query, "c"))
      textQuery.addPersonalQuery(copyFieldQuery(query, "title"))
      addSiteQuery(textQuery, queryText, query)
      if (isNumericTermQuery(query) && textQuery.getBoost() >= 1.0f) textQuery.setBoost(0.5f)
    }

    getStemmedFieldQuery("ts", queryText).foreach{ query =>
      textQuery.stems = extractTerms(query)
      if(!quoted) {
        textQuery.addRegularQuery(query)
        textQuery.addRegularQuery(copyFieldQuery(query, "cs"))
        textQuery.addPersonalQuery(copyFieldQuery(query, "title_stemmed"))
      }
    }
    if (textQuery.isEmpty) None else Some(textQuery)
  }

  private def extractTerms(query: Query): Array[Term] = {
    query match {
      case q: TermQuery => Array(q.getTerm)
      case q: PhraseQuery => q.getTerms
      case _ => Array.empty[Term]
    }
  }

  private def addConcatQuery(textQuery: TextQuery, concat: (String, String)): Unit = {
    val (t1, t2) = concat

    textQuery.concatStems += t2

    textQuery.addRegularQuery(new TermQuery(new Term("t", t1)), concatBoost)
    textQuery.addRegularQuery(new TermQuery(new Term("c", t1)), concatBoost)
    textQuery.addRegularQuery(new TermQuery(new Term("site_keywords", t1)), concatBoost)
    textQuery.addPersonalQuery(new TermQuery(new Term("title", t1)), concatBoost)

    textQuery.addRegularQuery(new TermQuery(new Term("ts", t2)), concatBoost)
    textQuery.addRegularQuery(new TermQuery(new Term("cs", t2)), concatBoost)
    textQuery.addPersonalQuery(new TermQuery(new Term("title_stemmed", t2)), concatBoost)
  }

  private def concat(q1: TextQuery, q2: TextQuery): (String, String) = {
    val sb =  new StringBuilder

    def append(terms: Array[Term], off: Int, end: Int): Unit = {
      if (off >= 0 && off < end) {
        var i = off
        while (i < end) {
          sb ++= terms(i).text
          i += 1
        }
      }
    }

    append(q1.terms, 0, q1.terms.length)
    append(q2.terms, 0, q2.terms.length - 1)
    val len = sb.length

    append(q2.terms, q2.terms.length - 1, q2.terms.length)
    val t1 = sb.toString

    sb.setLength(len)
    append(q2.stems, q2.stems.length - 1, q2.stems.length)
    val t2 = sb.toString

    (t1, t2)
  }

  override protected def buildQuery(querySpecList: List[QuerySpec]): Option[Query] = {
    val emptyQuery = new TextQuery
    val clauses = ArrayBuffer.empty[BooleanClause]
    val queries = ArrayBuffer.empty[(QuerySpec, TextQuery)]

    querySpecList.foreach{ spec =>
      val query = getFieldQuery(spec.field, spec.term, spec.quoted)
      query match {
        case Some(query) =>
          clauses += new BooleanClause(query, spec.occur)
          query match {
            case textQuery: TextQuery => queries += ((spec, textQuery))
            case _ => queries += ((spec, null))
          }
        case _ =>
          queries += ((spec, null))
      }
    }

    if (concatBoost > 0.0f && clauses.size <= 42) { // if we have too many terms, don't concat terms
      var prevTextQuery: TextQuery = null
      queries.foreach{ case (spec, currTextQuery) =>
        if (currTextQuery != null && !spec.quoted && spec.occur == SHOULD) {
          // concat phrase in the current query
          if (currTextQuery.terms.length > 1) {
            val c = concat(emptyQuery, currTextQuery)
            addConcatQuery(currTextQuery, c)
          }
          // concat with the previous query
          if (prevTextQuery != null) {
            val c = concat(prevTextQuery, currTextQuery)
            addConcatQuery(prevTextQuery, c)
            addConcatQuery(currTextQuery, c)
          }
          prevTextQuery = currTextQuery
        } else {
          prevTextQuery = null
        }
      }
    }

    getBooleanQuery(clauses)
  }
}
