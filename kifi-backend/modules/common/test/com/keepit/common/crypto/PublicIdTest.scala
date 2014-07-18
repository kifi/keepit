package com.keepit.common.crypto

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.db.Id
import org.specs2.mutable._

class PublicIdTest extends Specification {

  "PublicId" should {
    implicit val config = PublicIdConfiguration("secret key")

    "encode ids" in {
      case class TestModel(id: Option[Id[TestModel]]) extends ModelWithPublicId[TestModel]
      object TestModel extends ModelWithPublicIdCompanion[TestModel] {
        val prefix = "t"
        protected[this] val prefixIvSpec = new IvParameterSpec(Array(-72, -49, 51, -61, 42, 43, 123, -61, 64, 122, -121, -55, 117, -51, 12, 21))
      }

      case class TestModel2(id: Option[Id[TestModel2]]) extends ModelWithPublicId[TestModel2]
      object TestModel2 extends ModelWithPublicIdCompanion[TestModel2] {
        val prefix = "w"
        protected[this] val prefixIvSpec = new IvParameterSpec(Array(0, 0, 0, 0, -10, 100, 100, 10, 42, 42, 42, 42, 42, 42, 42, 42))
      }

      val id1 = TestModel(id = Option(Id[TestModel](1L))).id.get
      val id2 = TestModel(id = Option(Id[TestModel](2L))).id.get
      val id3 = TestModel2(id = Option(Id[TestModel2](1L))).id.get
      val id4 = TestModel2(id = Option(Id[TestModel2](2L))).id.get

      val pid1 = TestModel.publicId(id1).get
      val pid2 = TestModel.publicId(id2).get
      val pid3 = TestModel2.publicId(id3).get
      val pid4 = TestModel2.publicId(id4).get

      // Inputs are different, sanity
      id1 !== id2
      id2 !== id3
      id3 !== id4

      // PublicIds should be different
      pid1 !== pid2
      pid1 !== pid3
      pid1.id.substring(1) !== pid3.id.substring(1) // different IVs
      pid2 !== pid3
      pid3 !== pid4

      // Values are correct (sensitive to changes to IV and key)
      pid1.id === "tKhpz2uJdFZB"
      pid3.id === "w9wcfGVcIpAT"

      // Encryption is consistant
      TestModel.publicId(Id[TestModel](1)).get === pid1

      // Decryption works
      val id1_2 = TestModel.publicId(pid1).get
      id1_2 === id1

      // Description with the wrong key usually fails
      TestModel.publicId(pid1)(PublicIdConfiguration("otherkey")).get must throwAn[IllegalArgumentException]

    }
  }
}
