package com.keepit.search.message

import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.Searcher
import com.keepit.search.query.ConditionalQuery
import com.keepit.common.strings.UTF8

import org.apache.lucene.index.Term
import org.apache.lucene.search.{ Query, TermQuery }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.util.BytesRef

import play.api.libs.json.{ Json, JsValue, JsString }

import scala.collection.mutable.ArrayBuffer

case class ResultWithScore(score: Float, value: String)

class MessageSearcher(searcher: Searcher) {

  //not super effcient. we'll see how it behaves -Stephen
  def search(userId: Id[User], query: Query, from: Int = 0, howMany: Int = 20): Seq[JsValue] = {

    val participantFilterQuery = new TermQuery(new Term(ThreadIndexFields.participantIdsField, userId.id.toString))
    val filterdQuery = new ConditionalQuery(query, participantFilterQuery)

    val allResults = ArrayBuffer[ResultWithScore]()
    searcher.doSearch(filterdQuery) { (scorer, reader) =>
      val resultDocVals = reader.getBinaryDocValues(ThreadIndexFields.resultField)
      var docNumber = scorer.nextDoc()
      while (docNumber != NO_MORE_DOCS) {
        val resultBytes = new BytesRef()
        resultDocVals.get(scorer.docID(), resultBytes)
        val resultString = new String(resultBytes.bytes, resultBytes.offset, resultBytes.length, UTF8)
        allResults.append(
          ResultWithScore(
            scorer.score(),
            resultString
          )
        )
        docNumber = scorer.nextDoc()
      }
    }

    val orderedResults = allResults.sortWith {
      case (a, b) =>
        a.score > b.score
    }.drop(from).take(howMany)

    orderedResults.map { x =>
      try {
        Json.parse(x.value)
      } catch {
        case _: Throwable => Json.obj(
          "err" -> JsString(x.value)
        )
      }
    }

  }
}
