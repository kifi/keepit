package com.keepit.search.index.graph.organization

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }

import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.search.{ Lang, LangDetector }
import com.keepit.search.index.{ Searcher, DefaultAnalyzer, Indexable }
import org.apache.lucene.store.{ InputStreamDataInput, OutputStreamDataOutput }

object OrganizationFields {
  val nameField = "t"
  val namePrefixField = "tp"
  val nameValueField = "tv"
  val descriptionField = "c"
  val descriptionStemmedField = "cs"
  val ownerField = "o"
  val ownerIdField = "oid"
  val handleField = "h"
  val recordField = "rec"

  val maxPrefixLength = 8
}

object OrganizationIndexable {
  def getName(orgSearcher: Searcher, orgId: Long): Option[String] = {
    orgSearcher.getStringDocValue(OrganizationFields.nameValueField, orgId)
  }

  def getRecord(orgSearcher: Searcher, orgId: Id[Organization]): Option[OrganizationRecord] = {
    orgSearcher.getDecodedDocValue(OrganizationFields.recordField, orgId.id)
  }
}

class OrganizationIndexable(organization: IngestableOrganization) extends Indexable[Organization, Organization] {
  val id = organization.id.get
  val sequenceNumber = organization.seq
  val isDeleted: Boolean = organization.state == OrganizationStates.INACTIVE

  override def buildDocument = {
    import OrganizationFields._

    val doc = super.buildDocument
    val lang = Lang("en")
    doc.add(buildTextField(nameField, organization.name, DefaultAnalyzer.getAnalyzer(lang)))
    doc.add(buildPrefixField(namePrefixField, organization.name, maxPrefixLength))
    doc.add(buildStringDocValuesField(nameValueField, organization.name))

    organization.description.foreach { desc =>
      val descLang = LangDetector.detect(desc)
      doc.add(buildTextField(descriptionField, desc, DefaultAnalyzer.getAnalyzer(descLang)))
      doc.add(buildTextField(descriptionStemmedField, desc, DefaultAnalyzer.getAnalyzerWithStemmer(descLang)))
    }

    doc.add(buildKeywordField(ownerField, organization.ownerId.id.toString))
    doc.add(buildIdValueField(ownerIdField, organization.ownerId))

    doc.add(buildTextField(handleField, organization.handle.value, DefaultAnalyzer.getAnalyzer(lang)))
    doc.add(buildBinaryDocValuesField(recordField, OrganizationRecord(organization)))

    doc
  }
}

case class OrganizationRecord(id: Id[Organization], name: String, description: Option[String], ownerId: Id[User], handle: OrganizationHandle)

object OrganizationRecord {
  def apply(org: IngestableOrganization): OrganizationRecord = OrganizationRecord(org.id.get, org.name, org.description, org.ownerId, org.handle)

  implicit def toByteArray(record: OrganizationRecord): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val out = new OutputStreamDataOutput(baos)

    out.writeByte(1) // version
    out.writeLong(record.id.id)
    out.writeString(record.name)
    out.writeString(record.description.getOrElse(""))
    out.writeLong(record.ownerId.id)
    out.writeString(record.handle.value)

    out.close()
    baos.close()

    baos.toByteArray()
  }

  implicit def fromByteArray(bytes: Array[Byte], offset: Int, length: Int): OrganizationRecord = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))
    val version = in.readByte().toInt
    version match {
      case 1 =>
        val id = Id[Organization](in.readLong())
        val name = in.readString()
        val description = Some(in.readString()).filter(_.nonEmpty)
        val ownerId = Id[User](in.readLong())
        val handle = OrganizationHandle(in.readString())
        OrganizationRecord(id, name, description, ownerId, handle)
      case _ => throw new Exception(s"invalid data [version=$version]")
    }
  }

}
