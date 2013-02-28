package com.keepit.common.db

import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.test._
import com.keepit.inject._
import scala.slick.lifted._
import scala.slick.driver.H2Driver.Implicit._
import scala.slick.driver._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.runner.JUnitRunner
import scala.slick.lifted.Query
import com.keepit.common.db.slick._
import org.joda.time.DateTime

@RunWith(classOf[JUnitRunner])
class SlickTest extends SpecificationWithJUnit {

  "Slick" should {

    "using driver abstraction" in {
      running(new ShoeboxApplication()) {

        case class Bar(
          id: Option[Id[Bar]] = None,
          name: String
        ) extends Model[Bar] {
          def withId(id: Id[Bar]): Bar = this.copy(id = Some(id))
          def withUpdateTime(now: DateTime) = this
        }

        //could be easily mocked up
        trait BarRepo extends Repo[Bar] {
          //here you may have model specific queries...
          def getByName(name: String)(implicit session: ROSession): Seq[Bar]
        }

        //we can abstract out much of the standard repo and have it injected/mocked out
        class BarRepoImpl(val db: DataBaseComponent) extends BarRepo with DbRepo[Bar] {
          import db.Driver.Implicit._ // here's the driver, abstracted away

          implicit object BarIdTypeMapper extends BaseTypeMapper[Id[Bar]] {
            def apply(profile: BasicProfile) = new IdMapperDelegate[Bar](profile)
          }

          override lazy val table = new RepoTable[Bar](db, "foo") {
            def name = column[String]("name")
            def * = id.? ~ name <> (Bar, Bar.unapply _)
          }

          def getByName(name: String)(implicit session: ROSession): Seq[Bar] = {
            val q = for ( f <- table if f.name is name ) yield (f)
            q.list
          }

          //only for testing
          def createTableForTesting()(implicit session: RWSession) = table.ddl.create
        }

        val repo: BarRepo = new BarRepoImpl(inject[DataBaseComponent])

        //just for testing you know...
        inject[Database].readWrite{ implicit session =>
          repo.asInstanceOf[BarRepoImpl].createTableForTesting() //only in test mode we should know about the implementation
          val fooA = repo.save(Bar(name = "A"))
          fooA.id.get.id === 1
          val fooB = repo.save(Bar(name = "B"))
          fooB.id.get.id === 2
        }

        inject[Database].readOnly{ implicit session =>
          repo.count(session) === 2
          val a = repo.getByName("A")
          a.size === 1
          a.head.name === "A"
        }
      }
    }

    "using external id" in {
      running(new ShoeboxApplication()) {

        case class Bar(
          id: Option[Id[Bar]] = None,
          externalId: ExternalId[Bar] = ExternalId(),
          name: String
        ) extends ModelWithExternalId[Bar] {
          def withId(id: Id[Bar]): Bar = this.copy(id = Some(id))
          def withUpdateTime(now: DateTime) = this
        }

        //could be easily mocked up
        trait BarRepo extends Repo[Bar] with RepoWithExternalId[Bar] {
          //here you may have model specific queries...
          def getByName(name: String)(implicit session: ROSession): Seq[Bar]
        }

        //we can abstract out much of the standard repo and have it injected/mocked out
        class BarRepoImpl(val db: DataBaseComponent) extends BarRepo with DbRepo[Bar] with ExternalIdColumnDbFunction[Bar] {
          import db.Driver.Implicit._ // here's the driver, abstracted away

          implicit object BarIdTypeMapper extends BaseTypeMapper[Id[Bar]] {
            def apply(profile: BasicProfile) = new IdMapperDelegate[Bar](profile)
          }

          override lazy val table = new RepoTable[Bar](db, "foo") with ExternalIdColumn[Bar] {
            def name = column[String]("name")
            def * = id.? ~ externalId ~ name <> (Bar, Bar.unapply _)
          }

          def getByName(name: String)(implicit session: ROSession): Seq[Bar] = {
            val q = for ( f <- table if f.name is name ) yield (f)
            q.list
          }

          //only for testing
          def createTableForTesting()(implicit session: RWSession) = table.ddl.create
        }

        val repo: BarRepo = new BarRepoImpl(inject[DataBaseComponent])

        //just for testing you know...
        val (b1, b2) = inject[Database].readWrite{ implicit session =>
          repo.asInstanceOf[BarRepoImpl].createTableForTesting() //only in test mode we should know about the implementation
          (repo.save(Bar(name = "A")), repo.save(Bar(name = "B")))
        }

        inject[Database].readOnly{ implicit session =>
          repo.count(session) === 2
          repo.get(b1.externalId) === b1
          repo.get(b2.externalId) === b2
        }
      }
    }

  }
}
