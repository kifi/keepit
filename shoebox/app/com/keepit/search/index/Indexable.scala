package com.keepit.search.index

import com.keepit.common.db.Id
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.index.Payload
import org.apache.lucene.index.Term
import java.io.IOException

trait Indexable[T] {

  val uid: Id[T]
  val idFieldName: String
  val idPayloadFieldName: String
  val idPayloadTermText = "ID"
  lazy val idTerm = new Term(idFieldName, uid.id.toString)
  
  def buildDocument: Document = {
    val doc = new Document()
    doc.add(new Field(idFieldName, uid.id.toString, Field.Store.NO, Field.Index.NOT_ANALYZED_NO_NORMS, Field.TermVector.NO))
    doc.add(new Field(idPayloadFieldName, new IdPayloadTokenStream))
    doc
  }
    
  protected def buildTextField(fieldName: String, fieldValue: String) = {
	new Field(fieldName, fieldValue, Field.Store.NO, Field.Index.ANALYZED, Field.TermVector.NO)
  }
  
  class IdPayloadTokenStream extends TokenStream {
    var returnToken = true;

    @throws(classOf[IOException])
    override def incrementToken(): Boolean = {
      returnToken match {
        case true =>
          val payloadAttr = addAttribute(classOf[PayloadAttribute])
          val buf = new Array[Byte](8)
          val id = uid.id
          buf(0) = id.toByte
          buf(1) = (id >> 8).toByte
          buf(2) = (id >> 16).toByte
          buf(3) = (id >> 24).toByte
          buf(4) = (id >> 32).toByte
          buf(5) = (id >> 40).toByte
          buf(6) = (id >> 48).toByte
          buf(7) = (id >> 56).toByte
          payloadAttr.setPayload(new Payload(buf))
          val termAttr: CharTermAttribute = addAttribute(classOf[CharTermAttribute]);
          termAttr.append(idPayloadTermText)
          returnToken = false
          true
        case false => false
      }
    }  
  }
}

