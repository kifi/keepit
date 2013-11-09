package com.keepit.search.message

import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.index.Searcher
import com.keepit.search.query.ConditionalQuery
import com.keepit.common.strings.UTF8

import org.apache.lucene.index.Term
import org.apache.lucene.search.{Query, TermQuery, QueryWrapperFilter}
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.util.BytesRef

import play.api.libs.json.{Json, JsValue, JsString, JsObject}

import scala.collection.mutable.ArrayBuffer

case class ResultWithScore(score: Float, value: String)

class MessageSearcher(searcher: Searcher){

  def search(userId: Id[User], query: Query, from: Int = 0, howMany: Int = 20): Seq[JsValue] = {
    val participantFilterQuery = new TermQuery(new Term(ThreadIndexFields.participantIdsField, userId.id.toString))
    val filterdQuery = new ConditionalQuery(query, participantFilterQuery)
    val filter = new QueryWrapperFilter(participantFilterQuery)

    searcher.search(query, filter, from+howMany).scoreDocs.map{ scoreDoc =>
      searcher.getDecodedDocValue(ThreadIndexFields.resultField, scoreDoc.doc){ case (data, offset, length) => 
        val resultString = new String(data, offset, length, UTF8)
        val resultJson = try { Json.parse(resultString).as[JsObject] } catch { case _:Throwable => Json.obj("err" -> JsString(resultString)) }
        resultJson.deepMerge(Json.obj(
          "score" -> scoreDoc.score
        ))
      }.get
    }
  }

} 
